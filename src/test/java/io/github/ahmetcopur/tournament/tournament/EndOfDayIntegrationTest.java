package io.github.ahmetcopur.tournament.tournament;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.ahmetcopur.tournament.support.IntegrationTestBase;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class EndOfDayIntegrationTest extends IntegrationTestBase {

    private Map<String, Object> member(long userId) {
        return jdbc.queryForMap("SELECT * FROM group_members WHERE user_id = ? ORDER BY id DESC LIMIT 1", userId);
    }

    private final ExecutorService background = Executors.newCachedThreadPool();

    /*
     * Review finding: the 20:00 cutoff. A request can arrive before 20:00 but wait on a row lock
     * until after it. Rule: it counts only if it is processed (holds its locks) before 20:00.
     */

    @Test
    void levelUpWaitingOnALockAcrossTheCutoffScoresNothing() throws Exception {
        openTournament();
        FullGroup g = fullGroup();
        long tr = g.player("TR");
        levelUp(tr, 1);

        clock.set(CLOSING_TIME.minusMillis(100));
        Future<Integer> pending;
        try (AutoCloseable lock = holdLock("SELECT 1 FROM users WHERE id = " + tr + " FOR NO KEY UPDATE")) {
            pending = background.submit(() -> levelUp(tr).andReturn().getResponse().getStatus());
            awaitBlockedOn("UPDATE users SET level");
            clock.set(CLOSING_TIME.plusMillis(1)); // the lock is released only after 20:00
        }
        assertThat(pending.get(10, TimeUnit.SECONDS)).isEqualTo(200);
        assertThat(score(tr)).as("no point for a level up processed after 20:00").isEqualTo(1);
    }

    /**
     * Review finding (third review): the clock was read after the player lock but before the
     * group lock. Something holding the group row without ending it (an admin query, a future
     * feature) would let a level up that waited past 20:00 score with its pre-20:00 time.
     */
    @Test
    void levelUpWaitingOnTheGroupLockAcrossTheCutoffScoresNothing() throws Exception {
        openTournament();
        FullGroup g = fullGroup();
        long tr = g.player("TR");
        levelUp(tr, 1);

        clock.set(CLOSING_TIME.minusMillis(100));
        Future<Integer> pending;
        String lockGroup = "SELECT 1 FROM tournament_groups WHERE id = " + g.groupId() + " FOR NO KEY UPDATE";
        try (AutoCloseable lock = holdLock(lockGroup)) {
            pending = background.submit(() -> levelUp(tr).andReturn().getResponse().getStatus());
            awaitBlockedOn("SELECT m.* FROM group_members");
            clock.set(CLOSING_TIME.plusMillis(1)); // released after 20:00, group still ACTIVE
        }
        assertThat(pending.get(10, TimeUnit.SECONDS)).isEqualTo(200);
        assertThat(score(tr)).as("no point for a level up that got its group lock after 20:00").isEqualTo(1);
    }

    @Test
    void levelUpWaitingWhileTheTournamentEndsLeavesTheRankingAlone() throws Exception {
        openTournament();
        FullGroup g = fullGroup();
        long tr = g.player("TR"), us = g.player("US");
        levelUp(us, 2);
        levelUp(tr, 1);

        clock.set(CLOSING_TIME.minusMillis(100));
        Future<Integer> pending;
        try (AutoCloseable lock = holdLock("SELECT 1 FROM users WHERE id = " + tr + " FOR NO KEY UPDATE")) {
            pending = background.submit(() -> levelUp(tr).andReturn().getResponse().getStatus());
            awaitBlockedOn("UPDATE users SET level");
            clock.set(CLOSING_TIME);
            // Ranks while the level up is still waiting. In the background with a timeout, so a
            // lock cycle fails the test instead of hanging it.
            Instant closing = clock.instant();
            assertThat(background.submit(() -> lifecycle.endOverdue(closing)).get(10, TimeUnit.SECONDS)).isEqualTo(1);
        }
        assertThat(pending.get(10, TimeUnit.SECONDS)).isEqualTo(200);
        assertThat(score(tr)).isEqualTo(1);
        assertThat(member(us)).containsEntry("final_rank", 1);
        assertThat(member(tr)).containsEntry("final_rank", 2);
    }

    @Test
    void joinWaitingOnALockAcrossTheCutoffIsClosed() throws Exception {
        openTournament();
        long player = newPlayer("TR");

        clock.set(CLOSING_TIME.minusMillis(100));
        Future<Integer> pending;
        try (AutoCloseable lock = holdLock("SELECT 1 FROM users WHERE id = " + player + " FOR NO KEY UPDATE")) {
            pending = background.submit(() -> join(player).andReturn().getResponse().getStatus());
            awaitBlockedOn("SELECT * FROM users WHERE id");
            clock.set(CLOSING_TIME.plusMillis(1));
        }
        assertThat(pending.get(10, TimeUnit.SECONDS)).isEqualTo(409);
        assertThat(coins(player)).isEqualTo(5000);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM group_members", Integer.class)).isZero();
    }

    @Test
    void endOfDayRanksPaysTopTwoAndRefundsUnfilledGroups() throws Exception {
        Tournament t = openTournament();
        FullGroup g = fullGroup();
        long tr = g.player("TR"), us = g.player("US"), gb = g.player("GB");
        levelUp(us, 3);   // US reaches 3 first
        levelUp(tr, 3);   // TR ties at 3, later
        levelUp(gb, 1);

        long waitingTr = newPlayer("TR");
        long waitingUs = newPlayer("US");
        join(waitingTr);
        join(waitingUs);
        assertThat(coins(waitingTr)).isEqualTo(4000);

        clock.set(CLOSING_TIME);
        assertThat(settle()).isEqualTo(1);

        assertThat(member(us)).containsEntry("final_rank", 1).containsEntry("reward_coins", 10_000);
        assertThat(member(tr)).containsEntry("final_rank", 2).containsEntry("reward_coins", 5_000);
        assertThat(member(gb)).containsEntry("final_rank", 3).containsEntry("reward_coins", 0);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM tournament_groups WHERE status <> 'ENDED'", Integer.class))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM tournaments WHERE id = ?", String.class, t.getId()))
                .isEqualTo("ENDED");

        // Unfilled group: fee back, no rank, no reward.
        assertThat(coins(waitingTr)).isEqualTo(5000);
        assertThat(coins(waitingUs)).isEqualTo(5000);
        assertThat(member(waitingTr)).containsEntry("final_rank", null).containsEntry("reward_coins", 0);

        // The final board rebuild succeeded and was recorded, so nothing will retry it.
        assertThat(jdbc.queryForObject("SELECT boards_finalized_at FROM tournaments WHERE id = ?", Object.class, t.getId()))
                .isNotNull();

        // Running the job again (say, on a second instance) changes nothing.
        assertThat(lifecycle.endOverdue(clock.instant())).isZero();
        assertThat(coins(waitingTr)).isEqualTo(5000);

        // The final Redis boards match Postgres.
        assertThat(redisCountryTotals(t.getId())).containsEntry("US", 3L);
        assertThat(body(groupBoard(g.groupId())).get(0).get("userId").asLong()).isEqualTo(us);
    }

    @Test
    void refundReturnsWhatEachPlayerPaidEvenIfTheFeeChanged() throws Exception {
        openTournament();
        long joinedToday = newPlayer("TR");
        long joinedEarlier = newPlayer("US");
        join(joinedToday);
        join(joinedEarlier);
        assertThat(member(joinedToday)).containsEntry("entry_fee", 1000L);

        // As if this player had joined before a config change, when the fee was 1,500.
        jdbc.update("UPDATE group_members SET entry_fee = 1500 WHERE user_id = ?", joinedEarlier);
        jdbc.update("UPDATE users SET coins = coins - 500 WHERE id = ?", joinedEarlier);
        assertThat(coins(joinedEarlier)).isEqualTo(3500);

        clock.set(CLOSING_TIME);
        lifecycle.endOverdue(clock.instant());

        assertThat(coins(joinedToday)).isEqualTo(5000);
        assertThat(coins(joinedEarlier)).isEqualTo(5000);
    }

    @Test
    void levelUpsAfterTheEndDoNotScore() throws Exception {
        openTournament();
        FullGroup g = fullGroup();
        long tr = g.player("TR");
        levelUp(tr, 2);

        // Past 20:00 but before the job ran: no points.
        clock.set(CLOSING_TIME.plusMillis(1));
        levelUp(tr).andExpect(status().isOk());
        assertThat(score(tr)).isEqualTo(2);

        lifecycle.endOverdue(clock.instant());
        levelUp(tr).andExpect(status().isOk()).andExpect(jsonPath("$.level").value(24));
        assertThat(score(tr)).isEqualTo(2);
    }

    @Test
    void claimAddsTheRewardOnce() throws Exception {
        openTournament();
        FullGroup g = fullGroup();
        long us = g.player("US");
        levelUp(us, 1);
        clock.set(CLOSING_TIME);
        lifecycle.endOverdue(clock.instant());

        long before = coins(us);
        claim(us).andExpect(status().isOk()).andExpect(jsonPath("$.coins").value(before + 10_000));
        claim(us).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NO_REWARD"));
        claim(g.player("GB")).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NO_REWARD"));
        claim(999_999).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("USER_NOT_FOUND"));
        assertThat(member(us)).containsEntry("reward_claimed", true);
    }

    @Test
    void unclaimedRewardBlocksTomorrowUntilClaimed() throws Exception {
        openTournament();
        FullGroup g = fullGroup();
        long winner = g.player("FR");
        levelUp(winner, 1);
        clock.set(CLOSING_TIME);
        lifecycle.endOverdue(clock.instant());

        clock.set(MORNING.plus(Duration.ofDays(1)));
        join(winner).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("UNCLAIMED_REWARD"));
        join(g.player("DE")).andExpect(status().isCreated()); // no reward, free to join

        claim(winner).andExpect(status().isOk());
        join(winner).andExpect(status().isCreated());
    }

    @Test
    void concurrentClaimsPayOnlyOnce() throws Exception {
        openTournament();
        FullGroup g = fullGroup();
        long us = g.player("US");
        levelUp(us, 1);
        clock.set(CLOSING_TIME);
        lifecycle.endOverdue(clock.instant());
        long before = coins(us);

        int threads = 10;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Integer>> statuses = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            statuses.add(pool.submit(() -> {
                go.await();
                return claim(us).andReturn().getResponse().getStatus();
            }));
        }
        go.countDown();
        List<Integer> codes = new ArrayList<>();
        for (Future<Integer> f : statuses) {
            codes.add(f.get());
        }
        pool.shutdown();

        assertThat(codes).filteredOn(c -> c == 200).hasSize(1);
        assertThat(codes).filteredOn(c -> c == 404).hasSize(threads - 1);
        assertThat(coins(us)).isEqualTo(before + 10_000);
    }
}
