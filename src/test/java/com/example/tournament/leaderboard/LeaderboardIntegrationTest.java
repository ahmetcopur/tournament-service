package com.example.tournament.leaderboard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.tournament.support.IntegrationTestBase;
import com.example.tournament.tournament.Tournament;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;

class LeaderboardIntegrationTest extends IntegrationTestBase {

    private ResultActions rank(long tournamentId, long userId) throws Exception {
        return mvc.perform(get("/tournaments/{id}/users/{userId}/rank", tournamentId, userId));
    }

    private ResultActions countryBoard(long tournamentId) throws Exception {
        return mvc.perform(get("/tournaments/{id}/countries/leaderboard", tournamentId));
    }

    private static List<Long> userIds(JsonNode board) {
        List<Long> ids = new ArrayList<>();
        board.forEach(row -> ids.add(row.get("userId").asLong()));
        return ids;
    }

    @Test
    void groupBoardOrdersByScoreThenByWhoGotThereFirst() throws Exception {
        Tournament t = openTournament();
        FullGroup g = fullGroup();
        long tr = g.player("TR"), us = g.player("US"), gb = g.player("GB"), de = g.player("DE"), fr = g.player("FR");

        levelUp(us, 2);   // US reaches 2 first
        levelUp(tr, 2);   // TR reaches 2 later
        levelUp(gb, 1);

        JsonNode board = body(groupBoard(g.groupId()).andExpect(status().isOk()));
        // Zero scores keep join order: DE joined before FR.
        assertThat(userIds(board)).containsExactly(us, tr, gb, de, fr);
        assertThat(board.get(0).get("score").asInt()).isEqualTo(2);
        assertThat(board.get(0).get("country").asText()).isEqualTo("US");
        assertThat(board.get(0).get("username").asText()).startsWith("player");

        rank(t.getId(), us).andExpect(jsonPath("$.rank").value(1));
        rank(t.getId(), tr).andExpect(jsonPath("$.rank").value(2));
        rank(t.getId(), fr).andExpect(jsonPath("$.rank").value(5));
    }

    /**
     * Review finding: same score in the same millisecond. Redis orders equal values by member
     * string, Postgres by its own tie-break; the live board must match the final ranking.
     */
    @Test
    void exactTiesRankTheSameLiveAsInTheFinalRanking() throws Exception {
        Tournament t = openTournament();
        FullGroup g = fullGroup();
        long gb = g.player("GB"), de = g.player("DE");

        // The clock doesn't move between these: both reach score 1 in the same millisecond.
        levelUp(gb);
        levelUp(de);

        List<Long> live = userIds(body(groupBoard(g.groupId())));
        List<Integer> liveRanks = new ArrayList<>();
        for (long player : live) {
            liveRanks.add(body(rank(t.getId(), player)).get("rank").asInt());
        }

        clock.set(CLOSING_TIME);
        lifecycle.endOverdue(clock.instant());
        List<Long> fin = jdbc.queryForList(
                "SELECT user_id FROM group_members WHERE group_id = ? ORDER BY final_rank", Long.class, g.groupId());

        assertThat(live).as("live board vs final ranking").isEqualTo(fin);
        assertThat(liveRanks).containsExactly(1, 2, 3, 4, 5);
    }

    @Test
    void countryBoardSumsScoresAcrossGroups() throws Exception {
        Tournament t = openTournament();
        FullGroup first = fullGroup();
        FullGroup second = fullGroup();

        levelUp(first.player("TR"), 2);
        levelUp(second.player("TR"), 1);
        levelUp(second.player("US"), 1);

        countryBoard(t.getId()).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(5))
                .andExpect(jsonPath("$[0].country").value("TR"))
                .andExpect(jsonPath("$[0].score").value(3))
                .andExpect(jsonPath("$[1].country").value("US"))
                .andExpect(jsonPath("$[1].score").value(1));
    }

    @Test
    void boardsSurviveARedisWipe() throws Exception {
        Tournament t = openTournament();
        FullGroup g = fullGroup();
        levelUp(g.player("DE"), 3);
        levelUp(g.player("TR"), 1);
        String before = body(groupBoard(g.groupId())).toString();
        String countriesBefore = body(countryBoard(t.getId())).toString();

        flushRedis();

        // Answers come from Postgres and match what Redis said before.
        assertThat(body(groupBoard(g.groupId())).toString()).isEqualTo(before);
        assertThat(body(countryBoard(t.getId())).toString()).isEqualTo(countriesBefore);
        rank(t.getId(), g.player("DE")).andExpect(jsonPath("$.rank").value(1));
        // The group read healed the Redis board.
        assertThat(redis.opsForZSet().zCard("lb:group:" + g.groupId())).isEqualTo(5);

        // The next level up finds the country board missing and rebuilds it from Postgres.
        levelUp(g.player("GB"), 1);
        countryBoard(t.getId())
                .andExpect(jsonPath("$[0].country").value("DE"))
                .andExpect(jsonPath("$[0].score").value(3));
        assertThat(redis.opsForZSet().score(LeaderboardKeys.country(t.getId()), "GB")).isEqualTo(1.0);
        assertThat(redis.opsForZSet().score(LeaderboardKeys.country(t.getId()), "TR")).isEqualTo(1.0);
    }

    @Test
    void boardsExpireTwoDaysAfterTheTournamentEnds() throws Exception {
        Tournament t = openTournament();
        FullGroup g = fullGroup();
        levelUp(g.player("TR"));

        long expected = t.getEndsAt().getEpochSecond() + 2 * 24 * 3600;
        Long groupTtl = redis.getExpire("lb:group:" + g.groupId());
        long now = System.currentTimeMillis() / 1000;
        // TTLs are relative to the real clock, so compare absolute expiry times with some slack.
        assertThat(now + groupTtl).isBetween(expected - 5, expected + 5);
        assertThat(now + redis.getExpire(LeaderboardKeys.country(t.getId()))).isBetween(expected - 5, expected + 5);
        assertThat(now + redis.getExpire(LeaderboardKeys.countryApplied(t.getId()))).isBetween(expected - 5, expected + 5);
    }

    @Test
    void waitingGroupBoardComesFromPostgres() throws Exception {
        openTournament();
        long groupId = body(join(newPlayer("TR"))).get("groupId").asLong();
        join(newPlayer("US"));

        groupBoard(groupId).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].country").value("TR"));
        assertThat(redis.hasKey("lb:group:" + groupId)).isFalse();
    }

    @Test
    void unknownIdsAre404() throws Exception {
        Tournament t = openTournament();
        groupBoard(987_654).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("GROUP_NOT_FOUND"));
        rank(t.getId(), newPlayer("TR")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_IN_TOURNAMENT"));
        countryBoard(987_654).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TOURNAMENT_NOT_FOUND"));
    }
}
