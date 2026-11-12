package io.github.ahmetcopur.tournament.tournament;

import io.github.ahmetcopur.tournament.leaderboard.LeaderboardEntry;
import java.util.List;

public record JoinResponse(long groupId, TournamentGroup.Status status, List<LeaderboardEntry> leaderboard) {
}
