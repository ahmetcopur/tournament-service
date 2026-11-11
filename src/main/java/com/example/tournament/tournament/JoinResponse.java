package com.example.tournament.tournament;

import com.example.tournament.leaderboard.LeaderboardEntry;
import java.util.List;

public record JoinResponse(long groupId, TournamentGroup.Status status, List<LeaderboardEntry> leaderboard) {
}
