package com.example.tournament.leaderboard;

/**
 * Redis key names. Group boards are independent keys, so they spread across a Redis Cluster.
 * A tournament's country board and its bookkeeping share the {tournamentId} hash tag, so one
 * script can update them together.
 */
public final class LeaderboardKeys {

    private LeaderboardKeys() {
    }

    public static String group(long groupId) {
        return "lb:group:" + groupId;
    }

    /** Sorted set: country -> total score. */
    public static String country(long tournamentId) {
        return "lb:country:{" + tournamentId + "}";
    }

    /** Hash: user id -> highest score already counted on the country board, plus "_ready". */
    public static String countryApplied(long tournamentId) {
        return country(tournamentId) + ":applied";
    }

    /** Lock so only one instance rebuilds a country board at a time. */
    public static String countryRebuildLock(long tournamentId) {
        return country(tournamentId) + ":rebuild";
    }
}
