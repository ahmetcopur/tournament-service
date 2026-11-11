package com.example.tournament.leaderboard;

public record LeaderboardEntry(long userId, String username, String country, int score) {
}
