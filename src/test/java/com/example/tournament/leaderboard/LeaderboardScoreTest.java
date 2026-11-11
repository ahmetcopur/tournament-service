package com.example.tournament.leaderboard;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class LeaderboardScoreTest {

    private static final Instant START = Instant.parse("2026-11-11T00:00:00Z");

    @Test
    void higherScoreAlwaysWins() {
        long lateFive = LeaderboardScore.encode(5, START.plus(Duration.ofHours(19)), START);
        long earlyFour = LeaderboardScore.encode(4, START, START);
        assertThat(lateFive).isGreaterThan(earlyFour);
    }

    @Test
    void equalScoreReachedEarlierRanksHigher() {
        long early = LeaderboardScore.encode(7, START.plusMillis(1_000), START);
        long late = LeaderboardScore.encode(7, START.plusMillis(1_001), START);
        assertThat(early).isGreaterThan(late);
    }

    @Test
    void decodesBackToTheScore() {
        for (int score : new int[] {0, 1, 42, 9_000_000}) {
            for (Duration offset : new Duration[] {Duration.ZERO, Duration.ofMillis(1), Duration.ofHours(20)}) {
                long value = LeaderboardScore.encode(score, START.plus(offset), START);
                // Redis hands the value back as a double, so round-trip through one.
                assertThat(LeaderboardScore.decode((double) value)).isEqualTo(score);
            }
        }
    }

    @Test
    void largeValuesStayExactInADouble() {
        long value = LeaderboardScore.encode(9_000_000, START.plusMillis(123), START);
        assertThat((long) (double) value).isEqualTo(value);
    }
}
