package com.example.tournament.leaderboard;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.tournament.support.IntegrationTestBase;
import com.example.tournament.tournament.Tournament;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Review finding: a transaction that commits but whose Redis write never arrives (Redis timed
 * out, or the instance died right after commit) used to leave the boards stale until that
 * player's next update or 20:00.
 */
class RedisRepairIntegrationTest extends IntegrationTestBase {

    @Autowired
    private LeaderboardWriter writer;
    @Autowired
    private LeaderboardService leaderboards;

    private Map<String, Long> postgresCountryTotals(long tournamentId) {
        Map<String, Long> totals = new TreeMap<>();
        jdbc.query("""
                SELECT m.country, sum(m.score) FROM group_members m
                JOIN tournament_groups g ON g.id = m.group_id
                WHERE m.tournament_id = ? AND g.started_at IS NOT NULL GROUP BY m.country
                """, rs -> {
            totals.put(rs.getString(1), rs.getLong(2));
        }, tournamentId);
        return totals;
    }

    private Map<String, Long> redisCountryTotals(long tournamentId) {
        Map<String, Long> totals = new TreeMap<>();
        redis.opsForZSet().rangeWithScores(LeaderboardKeys.country(tournamentId), 0, -1)
                .forEach(t -> totals.put(t.getValue(), t.getScore().longValue()));
        return totals;
    }

    private int redisGroupScore(long groupId, long userId) {
        Double value = redis.opsForZSet().score(LeaderboardKeys.group(groupId), Long.toString(userId));
        return value == null ? -1 : LeaderboardScore.decode(value);
    }

    @Test
    void writesThatFailedAreRetriedFromPostgres() throws Exception {
        Tournament t = openTournament();
        FullGroup g = fullGroup();
        long tr = g.player("TR"), us = g.player("US");
        levelUp(tr, 1);

        // Make the next writes for this group fail for real: Redis rejects ZADD on a key of the
        // wrong type, so nothing of these updates reaches either board.
        String groupKey = LeaderboardKeys.group(g.groupId());
        redis.delete(groupKey);
        redis.opsForValue().set(groupKey, "broken");
        levelUp(us, 2); // commits in Postgres; the Redis writes fail
        levelUp(tr, 1);
        assertThat(score(us)).isEqualTo(2);
        assertThat(redisCountryTotals(t.getId())).as("Redis missed the updates").containsEntry("US", 0L);

        redis.delete(groupKey); // Redis is healthy again
        assertThat(writer.retryMissedWrites()).isEqualTo(1);

        assertThat(redisGroupScore(g.groupId(), us)).isEqualTo(2);
        assertThat(redisGroupScore(g.groupId(), tr)).isEqualTo(2);
        assertThat(redisCountryTotals(t.getId())).isEqualTo(postgresCountryTotals(t.getId()));
        assertThat(writer.retryMissedWrites()).as("nothing left to retry").isZero();
    }

    /**
     * A timeout doesn't mean the write was lost: with Redis frozen, the command already sits in
     * its socket buffer and runs when Redis resumes. The retry then applies the same update a
     * second time, which must not count any point twice.
     */
    @Test
    void timedOutWritesThatLandLateAreNotCountedTwice() throws Exception {
        Tournament t = openTournament();
        FullGroup g = fullGroup();
        long tr = g.player("TR"), us = g.player("US");
        levelUp(tr, 1);

        pauseRedis();
        try {
            levelUp(us, 2); // commits in Postgres; the Redis calls time out after 500 ms
            levelUp(tr, 1);
        } finally {
            unpauseRedis();
        }
        writer.retryMissedWrites();

        assertThat(redisGroupScore(g.groupId(), us)).isEqualTo(2);
        assertThat(redisGroupScore(g.groupId(), tr)).isEqualTo(2);
        assertThat(redisCountryTotals(t.getId())).isEqualTo(postgresCountryTotals(t.getId()))
                .containsEntry("US", 2L).containsEntry("TR", 2L);
    }

    @Test
    void periodicReapplyRepairsUpdatesThatNeverReachedRedis() throws Exception {
        Tournament t = openTournament();
        FullGroup g = fullGroup();
        long de = g.player("DE");
        levelUp(de, 1);

        // As if the instance died between the Postgres commit and its Redis write: Postgres has
        // two more points that Redis never heard about, and no failure was recorded anywhere.
        jdbc.update("UPDATE group_members SET score = score + 2 WHERE user_id = ?", de);
        assertThat(redisGroupScore(g.groupId(), de)).isEqualTo(1);

        writer.reapplyOpenTournaments();

        assertThat(redisGroupScore(g.groupId(), de)).isEqualTo(3);
        assertThat(redisCountryTotals(t.getId())).isEqualTo(postgresCountryTotals(t.getId()))
                .containsEntry("DE", 3L);
        assertThat(leaderboards.groupLeaderboard(g.groupId()).get(0).userId()).isEqualTo(de);
    }
}
