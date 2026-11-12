package com.example.tournament.leaderboard;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.tournament.support.IntegrationTestBase;
import com.example.tournament.tournament.GroupMemberRepository;
import com.example.tournament.tournament.Tournament;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.script.RedisScript;

/** The board code at a size where paging, incremental re-apply and memory actually matter. */
class LeaderboardScaleIntegrationTest extends IntegrationTestBase {

    private static final int PLAYERS = 2_000; // 400 full groups, four pages of 500

    @Autowired
    private LeaderboardWriter writer;
    @Autowired
    private LeaderboardService leaderboards;
    @Autowired
    private GroupMemberRepository members;

    /** 2,000 players in 400 full groups, scores 0..49, last changed an hour before "now". */
    private Tournament bigTournament() {
        Tournament t = openTournament();
        Timestamp anHourAgo = Timestamp.from(clock.instant().minus(Duration.ofHours(1)));
        jdbc.update("""
                INSERT INTO users (username, country, level, coins)
                SELECT 'bulk' || i, (ARRAY['TR','US','GB','DE','FR'])[1 + i % 5], 20, 4000
                FROM generate_series(0, ? - 1) i""", PLAYERS);
        jdbc.update("""
                INSERT INTO tournament_groups (tournament_id, status, member_count, started_at)
                SELECT ?, 'ACTIVE', 5, ? FROM generate_series(1, ? / 5)""", t.getId(), anHourAgo, PLAYERS);
        jdbc.update("""
                WITH u AS (SELECT id, country, row_number() OVER (ORDER BY id) - 1 AS rn FROM users),
                     g AS (SELECT id, row_number() OVER (ORDER BY id) - 1 AS rn FROM tournament_groups
                           WHERE tournament_id = ?)
                INSERT INTO group_members (group_id, tournament_id, user_id, country, score, score_updated_at, entry_fee)
                SELECT g.id, ?, u.id, u.country, u.id % 50, ?, 1000 FROM u JOIN g ON g.rn = u.rn / 5""",
                t.getId(), t.getId(), anHourAgo);
        return t;
    }

    private Map<String, Long> postgresTotals(long tournamentId) {
        Map<String, Long> totals = new TreeMap<>();
        members.countryTotals(tournamentId).forEach(row -> totals.put((String) row[0], ((Number) row[1]).longValue()));
        return totals;
    }

    private static final RedisScript<Long> MEMORY_USAGE =
            RedisScript.of("return redis.call('MEMORY', 'USAGE', KEYS[1], 'SAMPLES', 0) or 0", Long.class);

    private long memoryUsage(String key) {
        Long bytes = redis.execute(MEMORY_USAGE, List.of(key));
        return bytes == null ? 0 : bytes;
    }

    private List<Long> userIds() {
        return jdbc.queryForList("SELECT user_id FROM group_members ORDER BY user_id", Long.class);
    }

    @Test
    void rebuildPagesThroughATournamentAndMatchesPostgres() {
        Tournament t = bigTournament();

        assertThat(writer.rebuild(t.getId())).isTrue();

        assertThat(countryBoardComplete(t.getId())).isTrue();
        assertThat(redisCountryTotals(t.getId())).isEqualTo(postgresTotals(t.getId()));
        long lastGroup = jdbc.queryForObject("SELECT max(id) FROM tournament_groups", Long.class);
        assertThat(redis.opsForZSet().zCard(LeaderboardKeys.group(lastGroup))).isEqualTo(5); // from the last page
        assertThat(leaderboards.countryLeaderboard(t.getId())).hasSize(5);
    }

    @Test
    void bookkeepingIsCompactWhileOpenAndGoneOnceFinal() throws Exception {
        Tournament t = bigTournament();
        writer.rebuild(t.getId());

        // What the bookkeeping takes now: small bucket hashes per slice.
        Set<String> bucketKeys = new HashSet<>();
        for (long user : userIds()) {
            bucketKeys.add(layout.bucketKey(t.getId(), layout.shard(user), layout.bucket(user)));
        }
        long bucketed = bucketKeys.stream().mapToLong(this::memoryUsage).sum();

        // What one plain hash with a field per player would take, for comparison.
        Map<String, String> sameData = new HashMap<>();
        userIds().forEach(user -> sameData.put(Long.toString(user), Long.toString(user % 50)));
        redis.opsForHash().putAll("compare:plain-hash", sameData);
        long plain = memoryUsage("compare:plain-hash");

        System.out.printf("Country board bookkeeping for %d players: %d bytes in %d buckets (%.1f B/player), "
                + "one plain hash: %d bytes (%.1f B/player)%n", PLAYERS, bucketed, bucketKeys.size(),
                (double) bucketed / PLAYERS, plain, (double) plain / PLAYERS);
        assertThat(bucketed).as("bucketed bookkeeping vs one big hash").isLessThan(plain / 2);

        // After 20:00 the final rebuild runs, then the bookkeeping is deleted.
        clock.set(CLOSING_TIME);
        settle();
        assertThat(bucketKeys).allSatisfy(key -> assertThat(redis.hasKey(key)).as(key).isFalse());
        assertThat(countryBoardComplete(t.getId())).isTrue();
        assertThat(redisCountryTotals(t.getId())).isEqualTo(postgresTotals(t.getId()));
    }

    @Test
    void periodicReapplyOnlySendsWhatChangedSinceItsLastRun() {
        Tournament t = bigTournament();
        assertThat(writer.reapplyTournaments()).isEqualTo(1); // first run: everything
        Instant firstRun = clock.instant();
        assertThat(redisCountryTotals(t.getId())).isEqualTo(postgresTotals(t.getId()));

        clock.advance(Duration.ofMinutes(5));
        List<Long> users = userIds();
        long recent = users.get(10), untouched = users.get(20);
        // Changed after the first run, but its Redis write was lost: the next run must send it.
        jdbc.update("UPDATE group_members SET score = score + 5, score_updated_at = ? WHERE user_id = ?",
                Timestamp.from(clock.instant().minus(Duration.ofMinutes(1))), recent);
        // A row whose timestamp says it hasn't changed since well before the first run. The
        // incremental run doesn't read it, which is the point: rows that didn't change don't travel.
        jdbc.update("UPDATE group_members SET score = score + 7 WHERE user_id = ?", untouched);
        String recentCountry = jdbc.queryForObject("SELECT country FROM users WHERE id = ?", String.class, recent);
        String untouchedCountry = jdbc.queryForObject("SELECT country FROM users WHERE id = ?", String.class, untouched);
        Map<String, Long> before = redisCountryTotals(t.getId());

        redis.delete(LeaderboardKeys.reapplyLock(t.getId())); // the lock would otherwise hold for 4 minutes
        assertThat(writer.reapplyTournaments()).isEqualTo(1);

        Map<String, Long> after = redisCountryTotals(t.getId());
        assertThat(after.get(recentCountry) - before.get(recentCountry)).as("recent change sent").isEqualTo(5);
        if (!untouchedCountry.equals(recentCountry)) {
            assertThat(after.get(untouchedCountry)).as("old row not re-read").isEqualTo(before.get(untouchedCountry));
        }
        assertThat(Instant.ofEpochMilli(Long.parseLong(redis.opsForValue().get(LeaderboardKeys.reappliedAt(t.getId())))))
                .isAfter(firstRun);

        // A full rebuild still reads everything.
        writer.rebuild(t.getId());
        assertThat(redisCountryTotals(t.getId())).isEqualTo(postgresTotals(t.getId()));
    }
}
