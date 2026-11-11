package com.example.tournament.leaderboard;

import java.time.Duration;
import java.time.Instant;

/**
 * Packs score and "who got there first" into the one double a Redis sorted set allows.
 *
 * <pre>value = score * 10^9 + (10^9 - 1 - millis from tournament start to when the score was reached)</pre>
 *
 * The tie-break part sits in [0, 10^9), so a higher score always wins, and with an equal score
 * the earlier time wins. Milliseconds (not seconds) match the precision stored in Postgres, so
 * Redis and the 20:00 ranking order players the same way. The range covers windows up to 11 days,
 * and the whole value stays an exact integer in a double for scores up to about 9 million.
 */
public final class LeaderboardScore {

    static final long SCALE = 1_000_000_000L;

    private LeaderboardScore() {
    }

    public static long encode(int score, Instant reachedAt, Instant tournamentStart) {
        long millis = Duration.between(tournamentStart, reachedAt).toMillis();
        long clamped = Math.max(0, Math.min(SCALE - 1, millis));
        return score * SCALE + (SCALE - 1 - clamped);
    }

    public static int decode(double value) {
        return (int) (((long) value) / SCALE);
    }
}
