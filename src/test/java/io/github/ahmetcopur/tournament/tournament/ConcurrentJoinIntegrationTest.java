package io.github.ahmetcopur.tournament.tournament;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.ahmetcopur.tournament.support.IntegrationTestBase;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class ConcurrentJoinIntegrationTest extends IntegrationTestBase {

    private List<Integer> joinAllAtOnce(List<Long> userIds) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(userIds.size());
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Integer>> futures = new ArrayList<>();
        for (long id : userIds) {
            futures.add(pool.submit(() -> {
                go.await();
                return join(id).andReturn().getResponse().getStatus();
            }));
        }
        go.countDown();
        List<Integer> statuses = new ArrayList<>();
        for (Future<Integer> f : futures) {
            statuses.add(f.get(60, TimeUnit.SECONDS));
        }
        pool.shutdown();
        return statuses;
    }

    private int count(String sql) {
        return jdbc.queryForObject(sql, Integer.class);
    }

    @Test
    void twoHundredPlayersJoiningAtOnce() throws Exception {
        openTournament();
        List<Long> players = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            players.add(newPlayer(COUNTRIES.get(i % COUNTRIES.size())));
        }

        List<Integer> statuses = joinAllAtOnce(players);

        assertThat(statuses).as("every join succeeded").containsOnly(201);
        assertThat(count("SELECT count(*) FROM group_members")).isEqualTo(200);

        // No group has two players from one country.
        assertThat(count("""
                SELECT count(*) FROM (
                    SELECT group_id, country FROM group_members GROUP BY group_id, country HAVING count(*) > 1) d
                """)).isZero();

        // Every player was charged exactly once.
        assertThat(count("SELECT count(*) FROM users WHERE coins <> 4000")).isZero();

        // Group bookkeeping matches the members, and only full groups are ACTIVE.
        assertThat(count("""
                SELECT count(*) FROM tournament_groups g
                WHERE g.member_count <> (SELECT count(*) FROM group_members m WHERE m.group_id = g.id)
                """)).isZero();
        assertThat(count("""
                SELECT count(*) FROM tournament_groups
                WHERE (status = 'ACTIVE') <> (member_count = 5) OR member_count > 5
                   OR (status = 'ACTIVE') <> (started_at IS NOT NULL)
                """)).isZero();
        int active = count("SELECT count(*) FROM tournament_groups WHERE status = 'ACTIVE'");
        assertThat(active).isGreaterThan(0);

        // Each ACTIVE group made it to Redis with all five members.
        List<Long> activeIds = jdbc.queryForList("SELECT id FROM tournament_groups WHERE status = 'ACTIVE'", Long.class);
        for (long groupId : activeIds) {
            assertThat(redis.opsForZSet().zCard("lb:group:" + groupId)).isEqualTo(5);
        }
    }

    @Test
    void onePlayerJoiningManyTimesAtOnceIsChargedOnce() throws Exception {
        openTournament();
        long player = newPlayer("TR");

        List<Integer> statuses = joinAllAtOnce(java.util.Collections.nCopies(20, player));

        assertThat(statuses).filteredOn(s -> s == 201).hasSize(1);
        assertThat(statuses).filteredOn(s -> s == 409).hasSize(19);
        assertThat(coins(player)).isEqualTo(4000);
        assertThat(count("SELECT count(*) FROM group_members")).isEqualTo(1);
    }
}
