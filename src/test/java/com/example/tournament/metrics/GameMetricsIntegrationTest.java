package com.example.tournament.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.tournament.support.IntegrationTestBase;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** The counters and gauges behind the Grafana dashboard move when, and only when, they should. */
class GameMetricsIntegrationTest extends IntegrationTestBase {

    @Autowired
    private MeterRegistry meters;
    @Autowired
    private GameMetrics gameMetrics;

    private double counter(String name, String... tags) {
        var counter = meters.find(name).tags(tags).counter();
        return counter == null ? 0 : counter.count();
    }

    private double gauge(String name, String... tags) {
        return meters.get(name).tags(tags).gauge().value();
    }

    /** How much a counter grew while {@code action} ran (counters are shared across tests). */
    private double growth(Supplier<Double> read, ThrowingRunnable action) throws Exception {
        double before = read.get();
        action.run();
        return read.get() - before;
    }

    interface ThrowingRunnable {
        void run() throws Exception;
    }

    @Test
    void errorResponsesAreCountedByCode() throws Exception {
        assertThat(growth(() -> counter("api.errors", "code", "LEVEL_TOO_LOW"),
                () -> join(newPlayer("TR", 5, 5000)))).isEqualTo(1);
        assertThat(growth(() -> counter("api.errors", "code", "NOT_ENOUGH_COINS"),
                () -> join(newPlayer("TR", 20, 10)))).isEqualTo(1);
    }

    @Test
    void groupsFilledAndPointsScoredCountOnlyRealEvents() throws Exception {
        openTournament();
        long early = newPlayer("TR");
        join(early);

        // A level up in a WAITING group earns no point.
        assertThat(growth(() -> counter("game.points.scored"), () -> levelUp(early))).isZero();

        assertThat(growth(() -> counter("game.groups.filled"), () -> {
            for (String country : COUNTRIES.subList(1, COUNTRIES.size())) {
                join(newPlayer(country));
            }
        })).isEqualTo(1);

        assertThat(growth(() -> counter("game.points.scored"), () -> levelUp(early, 3))).isEqualTo(3);
        // The first group of a tournament builds the country board.
        assertThat(counter("leaderboard.country.rebuilds")).isGreaterThanOrEqualTo(1);
    }

    @Test
    void gaugesShowWaitingGroupsAndRefundsAreCounted() throws Exception {
        openTournament();
        fullGroup();
        join(newPlayer("TR"));
        join(newPlayer("US"));
        join(newPlayer("TR")); // second TR can't share a group: a second waiting group

        gameMetrics.refreshGroupGauges();
        assertThat(gauge("game.groups", "status", "ACTIVE")).isEqualTo(1);
        assertThat(gauge("game.groups", "status", "WAITING")).isEqualTo(2);
        assertThat(gauge("game.players.waiting")).isEqualTo(3);

        clock.set(CLOSING_TIME);
        assertThat(growth(() -> counter("game.refunds"), () -> lifecycle.endOverdue(clock.instant())))
                .isEqualTo(3);

        gameMetrics.refreshGroupGauges(); // tournament ended: nothing open any more
        assertThat(gauge("game.groups", "status", "WAITING")).isZero();
        assertThat(gauge("game.players.waiting")).isZero();
    }
}
