package io.github.ahmetcopur.tournament.tournament;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ahmetcopur.tournament.config.TimeSource;
import io.github.ahmetcopur.tournament.leaderboard.LeaderboardWriter;
import io.github.ahmetcopur.tournament.support.IntegrationTestBase;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Review finding: settlement ran only at 20:00 and at startup. If Postgres failed at 20:00, ranks,
 * rewards and refunds waited for the next day (or a restart). The every-minute reconcile must
 * settle it once the database is back, exactly once.
 */
class SettlementRetryIntegrationTest extends IntegrationTestBase {

    @Autowired
    private TournamentRepository tournaments;
    @Autowired
    private LeaderboardWriter writer;
    @Autowired
    private TimeSource time;

    /** The real jobs class (tests run with scheduling off, so we call it as the scheduler would). */
    private TournamentJobs jobs;

    @BeforeEach
    void installFaultSwitch() {
        jobs = new TournamentJobs(lifecycle, tournaments, writer, time);
        // A real database error on demand: while the switch is on, any update of a tournament
        // fails inside Postgres, exactly where settlement's first statement runs.
        jdbc.execute("CREATE TABLE IF NOT EXISTS test_fault (active boolean NOT NULL)");
        jdbc.execute("""
                CREATE OR REPLACE FUNCTION test_fail_tournament_update() RETURNS trigger AS $$
                BEGIN
                  IF EXISTS (SELECT 1 FROM test_fault WHERE active) THEN
                    RAISE EXCEPTION 'injected fault: database unavailable';
                  END IF;
                  RETURN NEW;
                END $$ LANGUAGE plpgsql""");
        jdbc.execute("""
                CREATE OR REPLACE TRIGGER test_fail_tournament_update BEFORE UPDATE ON tournaments
                FOR EACH ROW EXECUTE FUNCTION test_fail_tournament_update()""");
    }

    @AfterEach
    void removeFaultSwitch() {
        jdbc.execute("DROP TRIGGER IF EXISTS test_fail_tournament_update ON tournaments");
        jdbc.execute("DROP FUNCTION IF EXISTS test_fail_tournament_update()");
        jdbc.execute("DROP TABLE IF EXISTS test_fault");
    }

    private void databaseFails(boolean fails) {
        jdbc.update("DELETE FROM test_fault");
        jdbc.update("INSERT INTO test_fault VALUES (?)", fails);
    }

    private Map<String, Object> member(long userId) {
        return jdbc.queryForMap("SELECT * FROM group_members WHERE user_id = ?", userId);
    }

    @Test
    void settlementThatFailedAtTwentyHundredIsDoneByTheNextReconcileExactlyOnce() throws Exception {
        Tournament t = openTournament();
        FullGroup g = fullGroup();
        long us = g.player("US");
        levelUp(us, 2);
        long waiting = newPlayer("TR");
        join(waiting); // a group that never fills: its fee must come back
        assertThat(coins(waiting)).isEqualTo(4000);

        // 20:00, and the database fails during settlement.
        databaseFails(true);
        clock.set(CLOSING_TIME);
        jobs.closeDailyTournament();

        assertThat(jdbc.queryForObject("SELECT status FROM tournaments WHERE id = ?", String.class, t.getId()))
                .as("still ACTIVE after the failed run").isEqualTo("ACTIVE");
        assertThat(member(us)).containsEntry("final_rank", null);
        assertThat(coins(waiting)).isEqualTo(4000);

        // A minute later the database is back and the reconcile runs.
        databaseFails(false);
        clock.advance(Duration.ofMinutes(1));
        jobs.reconcileTournaments();

        assertThat(jdbc.queryForObject("SELECT status FROM tournaments WHERE id = ?", String.class, t.getId()))
                .isEqualTo("ENDED");
        assertThat(member(us)).containsEntry("final_rank", 1).containsEntry("reward_coins", 10_000);
        assertThat(coins(waiting)).as("refunded").isEqualTo(5000);

        // Later reconciles change nothing: no second refund, no second reward.
        clock.advance(Duration.ofMinutes(1));
        jobs.reconcileTournaments();
        jobs.reconcileTournaments();
        assertThat(coins(waiting)).isEqualTo(5000);
        assertThat(member(us)).containsEntry("reward_coins", 10_000);
    }
}
