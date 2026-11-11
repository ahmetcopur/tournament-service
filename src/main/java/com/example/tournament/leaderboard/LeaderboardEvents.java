package com.example.tournament.leaderboard;

import java.time.Instant;
import java.util.List;

/**
 * Published inside a transaction and applied to Redis only after it commits, so a rolled
 * back transaction never shows up on a leaderboard.
 */
public final class LeaderboardEvents {

    private LeaderboardEvents() {
    }

    public record Member(long userId, String country, Instant joinedAt) {
    }

    public record GroupActivated(long groupId, long tournamentId, Instant tournamentStart, Instant tournamentEnd,
                                 List<Member> members) {
    }

    public record ScoreChanged(long groupId, long tournamentId, long userId, String country, int score,
                               Instant reachedAt, Instant tournamentStart, Instant tournamentEnd) {
    }

    public record TournamentEnded(long tournamentId) {
    }
}
