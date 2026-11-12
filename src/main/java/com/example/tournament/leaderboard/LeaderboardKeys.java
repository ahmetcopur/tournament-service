package com.example.tournament.leaderboard;

/**
 * Redis key names for group boards and per-tournament locks. Group boards are independent keys,
 * so they spread across a Redis Cluster. Country board keys are in {@link CountryBoardLayout}.
 */
public final class LeaderboardKeys {

    private LeaderboardKeys() {
    }

    public static String group(long groupId) {
        return "lb:group:" + groupId;
    }

    /** Lock so only one instance rebuilds a tournament's country board at a time. */
    public static String countryRebuildLock(long tournamentId) {
        return "lb:country:{t" + tournamentId + "}:rebuild";
    }

    /** Lock that lets one instance per interval re-apply a tournament's boards from Postgres. */
    public static String reapplyLock(long tournamentId) {
        return "lb:country:{t" + tournamentId + "}:reapply";
    }

    /** When the last re-apply started (epoch millis); the next one only sends changes since then. */
    public static String reappliedAt(long tournamentId) {
        return "lb:country:{t" + tournamentId + "}:reapplied-at";
    }
}
