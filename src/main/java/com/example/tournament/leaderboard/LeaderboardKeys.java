package com.example.tournament.leaderboard;

public final class LeaderboardKeys {

    private LeaderboardKeys() {
    }

    public static String group(long groupId) {
        return "lb:group:" + groupId;
    }

    public static String country(long tournamentId) {
        return "lb:country:" + tournamentId;
    }
}
