package com.example.tournament.tournament;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.tournament.support.IntegrationTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JoinIntegrationTest extends IntegrationTestBase {

    @Test
    void fiveCountriesFillAGroupAndTurnItActive() throws Exception {
        openTournament();
        long groupId = -1;
        for (int i = 0; i < 4; i++) {
            JsonNode joined = body(join(newPlayer(COUNTRIES.get(i))).andExpect(status().isCreated()));
            assertThat(joined.get("status").asText()).isEqualTo("WAITING");
            assertThat(joined.get("leaderboard")).hasSize(i + 1);
            groupId = joined.get("groupId").asLong();
        }

        long last = newPlayer("FR");
        join(last).andExpect(status().isCreated())
                .andExpect(jsonPath("$.groupId").value(groupId))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.leaderboard.length()").value(5))
                .andExpect(jsonPath("$.leaderboard[0].score").value(0));

        Map<String, Object> group = jdbc.queryForMap("SELECT * FROM tournament_groups WHERE id = ?", groupId);
        assertThat(group.get("status")).isEqualTo("ACTIVE");
        assertThat(group.get("member_count")).isEqualTo(5);
        assertThat(group.get("started_at")).isNotNull();
        assertThat(coins(last)).isEqualTo(4000);
        assertThat(redis.opsForZSet().zCard("lb:group:" + groupId)).isEqualTo(5);
    }

    @Test
    void twoPlayersFromOneCountryLandInDifferentGroups() throws Exception {
        openTournament();
        long first = body(join(newPlayer("TR"))).get("groupId").asLong();
        long second = body(join(newPlayer("TR"))).get("groupId").asLong();
        long us = body(join(newPlayer("US"))).get("groupId").asLong();

        assertThat(second).isNotEqualTo(first);
        assertThat(us).isEqualTo(first); // oldest group with room for US
    }

    @Test
    void joiningOutsideTheWindowIsClosed() throws Exception {
        clock.set(CLOSING_TIME);
        long player = newPlayer("TR");
        join(player).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TOURNAMENT_CLOSED"));
        assertThat(coins(player)).isEqualTo(5000);
    }

    @Test
    void levelBelowTwentyIsRejected() throws Exception {
        join(newPlayer("TR", 19, 5000)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LEVEL_TOO_LOW"));
    }

    @Test
    void notEnoughCoinsIsRejected() throws Exception {
        long player = newPlayer("TR", 20, 999);
        join(player).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("NOT_ENOUGH_COINS"));
        assertThat(coins(player)).isEqualTo(999);
    }

    @Test
    void secondJoinTheSameDayIsRejectedAndNotCharged() throws Exception {
        long player = newPlayer("TR");
        join(player).andExpect(status().isCreated());
        join(player).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ALREADY_JOINED"));
        assertThat(coins(player)).isEqualTo(4000);
    }

    @Test
    void unclaimedRewardFromAnEarlierTournamentBlocksEntry() throws Exception {
        long player = newPlayer("TR");
        Instant yesterdayStart = MORNING.minus(Duration.ofDays(1)).truncatedTo(ChronoUnit.DAYS);
        long yesterday = jdbc.queryForObject(
                "INSERT INTO tournaments (starts_at, ends_at, status) VALUES (?, ?, 'ENDED') RETURNING id", Long.class,
                Timestamp.from(yesterdayStart), Timestamp.from(yesterdayStart.plus(Duration.ofHours(20))));
        long group = jdbc.queryForObject(
                "INSERT INTO tournament_groups (tournament_id, status, member_count) VALUES (?, 'ENDED', 5) RETURNING id",
                Long.class, yesterday);
        jdbc.update("""
                INSERT INTO group_members (group_id, tournament_id, user_id, country, score, final_rank, reward_coins)
                VALUES (?, ?, ?, 'TR', 9, 1, 10000)""", group, yesterday, player);

        join(player).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("UNCLAIMED_REWARD"));
        assertThat(coins(player)).isEqualTo(5000);
    }

    @Test
    void unknownUserIs404() throws Exception {
        join(424_242).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("USER_NOT_FOUND"));
    }

    @Test
    void scoreOnlyCountsOnceTheGroupIsFull() throws Exception {
        openTournament();
        long tr = newPlayer("TR");
        join(tr);
        for (String c : COUNTRIES.subList(1, 4)) {
            join(newPlayer(c));
        }

        levelUp(tr).andExpect(status().isOk()).andExpect(jsonPath("$.level").value(21));
        assertThat(score(tr)).isZero();

        join(newPlayer("FR")).andExpect(jsonPath("$.status").value("ACTIVE"));
        levelUp(tr);
        assertThat(score(tr)).isEqualTo(1);
    }
}
