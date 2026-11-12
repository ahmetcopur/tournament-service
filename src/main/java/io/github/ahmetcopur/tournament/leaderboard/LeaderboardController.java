package io.github.ahmetcopur.tournament.leaderboard;

import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class LeaderboardController {

    private final LeaderboardService leaderboards;

    public LeaderboardController(LeaderboardService leaderboards) {
        this.leaderboards = leaderboards;
    }

    @GetMapping("/groups/{groupId}/leaderboard")
    public List<LeaderboardEntry> group(@PathVariable long groupId) {
        return leaderboards.groupLeaderboard(groupId);
    }

    @GetMapping("/tournaments/{id}/users/{userId}/rank")
    public RankResponse rank(@PathVariable long id, @PathVariable long userId) {
        return leaderboards.rank(id, userId);
    }

    @GetMapping("/tournaments/{id}/countries/leaderboard")
    public List<CountryScore> countries(@PathVariable long id) {
        return leaderboards.countryLeaderboard(id);
    }
}
