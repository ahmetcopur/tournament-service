package com.example.tournament.leaderboard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.example.tournament.leaderboard.LeaderboardEvents.ScoreChanged;
import com.example.tournament.support.IntegrationTestBase;
import com.example.tournament.tournament.Tournament;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** The country board stays exact through retries, reordering, wipes and concurrent rebuilds. */
class CountryBoardIntegrationTest extends IntegrationTestBase {

    @Autowired
    private LeaderboardWriter writer;
    @Autowired
    private MeterRegistry meters;

    private Map<String, Long> postgresTotals(long tournamentId) {
        Map<String, Long> totals = new TreeMap<>();
        jdbc.query("""
                SELECT m.country, sum(m.score) FROM group_members m
                JOIN tournament_groups g ON g.id = m.group_id
                WHERE m.tournament_id = ? AND g.started_at IS NOT NULL GROUP BY m.country
                """, rs -> {
            totals.put(rs.getString(1), rs.getLong(2));
        }, tournamentId);
        return totals;
    }

    private Map<String, Long> apiTotals(long tournamentId) throws Exception {
        Map<String, Long> totals = new TreeMap<>();
        body(mvc.perform(get("/tournaments/{id}/countries/leaderboard", tournamentId).with(anyPlayer())))
                .forEach(row -> totals.put(row.get("country").asText(), row.get("score").asLong()));
        return totals;
    }

    /** A score update as the after-commit listener sees it, for replaying late or twice. */
    private ScoreChanged update(Tournament t, FullGroup g, String country, int score) {
        return new ScoreChanged(g.groupId(), t.getId(), g.player(country), country, score,
                clock.instant(), t.getStartsAt(), t.getEndsAt());
    }

    @Test
    void repeatedAndOutOfOrderUpdatesCountOnce() throws Exception {
        Tournament t = openTournament();
        FullGroup g = fullGroup();
        levelUp(g.player("TR"), 3);

        // A retried delivery, and older updates arriving after newer ones.
        writer.onScoreChanged(update(t, g, "TR", 3));
        writer.onScoreChanged(update(t, g, "TR", 1));
        writer.onScoreChanged(update(t, g, "TR", 2));

        assertThat(redisCountryTotals(t.getId())).containsEntry("TR", 3L).isEqualTo(postgresTotals(t.getId()));
    }

    @Test
    void exactAfterAWipeEvenWhenOlderUpdatesArriveLate() throws Exception {
        Tournament t = openTournament();
        FullGroup first = fullGroup();
        FullGroup second = fullGroup();
        levelUp(first.player("TR"), 2);
        levelUp(second.player("TR"), 1);
        levelUp(first.player("DE"), 4);

        flushRedis();
        assertThat(apiTotals(t.getId())).isEqualTo(postgresTotals(t.getId())); // served from Postgres

        // The next update finds the board gone, applies itself and rebuilds the rest from Postgres.
        levelUp(second.player("GB"), 1);
        assertThat(countryBoardComplete(t.getId())).isTrue();

        // Updates that committed before the wipe but reach Redis only now are already counted.
        writer.onScoreChanged(update(t, first, "TR", 2));
        writer.onScoreChanged(update(t, first, "DE", 3));

        assertThat(redisCountryTotals(t.getId())).isEqualTo(postgresTotals(t.getId()))
                .containsEntry("TR", 3L).containsEntry("DE", 4L).containsEntry("GB", 1L);
        assertThat(apiTotals(t.getId())).isEqualTo(postgresTotals(t.getId()));
    }

    @Test
    void readsComeFromPostgresWhileAnotherInstanceIsRebuilding() throws Exception {
        Tournament t = openTournament();
        FullGroup g = fullGroup();
        levelUp(g.player("US"), 2);

        flushRedis();
        String lock = LeaderboardKeys.countryRebuildLock(t.getId());
        redis.opsForValue().set(lock, "another-instance", Duration.ofMinutes(1));

        levelUp(g.player("FR"), 1);
        // The update was applied, but the board is incomplete (US's points are missing),
        // so it isn't served; the API answers from Postgres instead.
        assertThat(countryBoardComplete(t.getId())).isFalse();
        assertThat(redisCountryTotals(t.getId())).containsEntry("FR", 1L).doesNotContainKey("US");
        assertThat(apiTotals(t.getId())).isEqualTo(postgresTotals(t.getId())).containsEntry("US", 2L);

        // The other instance gave up; the next update takes over the rebuild.
        redis.delete(lock);
        levelUp(g.player("FR"), 1);
        assertThat(countryBoardComplete(t.getId())).isTrue();
        assertThat(redisCountryTotals(t.getId())).isEqualTo(postgresTotals(t.getId()));
    }

    @Test
    void exactUnderConcurrentLevelUpsWithAWipeHalfwayThrough() throws Exception {
        Tournament t = openTournament();
        List<Long> players = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            players.addAll(fullGroup().playerIds());
        }

        int total = 600;
        AtomicInteger done = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(32);
        List<Future<Integer>> statuses = new ArrayList<>();
        for (int i = 0; i < total; i++) {
            long player = players.get(i % players.size());
            statuses.add(pool.submit(() -> {
                int status = levelUp(player).andReturn().getResponse().getStatus();
                if (done.incrementAndGet() == total / 2) {
                    flushRedis(); // lose everything while updates keep landing
                }
                return status;
            }));
        }
        for (Future<Integer> f : statuses) {
            assertThat(f.get(60, TimeUnit.SECONDS)).isEqualTo(200);
        }
        pool.shutdown();

        assertThat(postgresTotals(t.getId()).values().stream().mapToLong(Long::longValue).sum()).isEqualTo(total);
        assertThat(countryBoardComplete(t.getId())).isTrue();
        assertThat(redisCountryTotals(t.getId())).isEqualTo(postgresTotals(t.getId()));
    }

    @Test
    void startupWithNoFullGroupBuildsNothingAndTheFirstGroupBuildsOnce() throws Exception {
        Tournament t = openTournament();
        double rebuildsBefore = meters.counter("leaderboard.country.rebuilds").count();

        writer.rebuildIfMissing(t.getId()); // what startup does
        for (int shard = 0; shard < layout.shards(); shard++) {
            assertThat(redis.hasKey(layout.meta(t.getId(), shard))).isFalse();
        }

        fullGroup();
        assertThat(countryBoardComplete(t.getId())).isTrue();
        assertThat(meters.counter("leaderboard.country.rebuilds").count() - rebuildsBefore).isEqualTo(1);
    }

    @Test
    void aSliceThatLostHalfOfItsKeysIsRebuiltWithoutDoubleCounting() throws Exception {
        Tournament t = openTournament();
        FullGroup g = fullGroup();
        long tr = g.player("TR");
        levelUp(tr, 2);
        int slice = layout.shard(tr);

        // Say eviction removed a slice's totals but left its bookkeeping. Trusting that
        // bookkeeping would add TR's 2 points never again; the slice starts a new epoch instead,
        // ignores the stale bookkeeping, and is rebuilt.
        redis.delete(layout.totals(t.getId(), slice));
        levelUp(tr, 1);
        assertThat(redisCountryTotals(t.getId())).isEqualTo(postgresTotals(t.getId())).containsEntry("TR", 3L);

        // And the other way round: the bookkeeping's anchor (meta) gone, totals left behind.
        redis.delete(layout.meta(t.getId(), slice));
        levelUp(tr, 1);
        assertThat(redisCountryTotals(t.getId())).isEqualTo(postgresTotals(t.getId())).containsEntry("TR", 4L);
        assertThat(countryBoardComplete(t.getId())).isTrue();
    }

    @Test
    void theBoardIsSplitIntoSlicesThatAddUp() throws Exception {
        Tournament t = openTournament();
        FullGroup g = fullGroup();
        for (long player : g.playerIds()) {
            levelUp(player, 1);
        }
        long slicesWithPoints = 0;
        for (int shard = 0; shard < layout.shards(); shard++) {
            Double sum = redis.opsForZSet().rangeWithScores(layout.totals(t.getId(), shard), 0, -1).stream()
                    .mapToDouble(x -> x.getScore()).sum();
            slicesWithPoints += sum > 0 ? 1 : 0;
        }
        assertThat(slicesWithPoints).as("five consecutive players land in five slices").isEqualTo(5);
        assertThat(apiTotals(t.getId())).isEqualTo(postgresTotals(t.getId()));
    }
}
