package com.example.tournament.leaderboard;

import com.example.tournament.config.GameProperties;
import com.example.tournament.config.TimeSource;
import com.example.tournament.leaderboard.LeaderboardEvents.GroupActivated;
import com.example.tournament.leaderboard.LeaderboardEvents.ScoreChanged;
import com.example.tournament.leaderboard.LeaderboardEvents.TournamentEnded;
import com.example.tournament.tournament.GroupMemberRepository;
import com.example.tournament.tournament.MemberScore;
import com.example.tournament.tournament.Tournament;
import com.example.tournament.tournament.TournamentRepository;
import com.example.tournament.tournament.TournamentWindows;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Applies committed changes to the Redis boards. Postgres stays the source of truth: a failed
 * Redis write is logged, counted and retried from Postgres, never surfaced to the player.
 *
 * <p>Every write is idempotent. Group boards keep each player's highest value (ZADD GT). The
 * country board records the highest score it has counted per player and adds only the
 * difference, so late, repeated or out-of-order updates and rebuilds racing live traffic can't
 * count a point twice or lose one.
 *
 * <p>Scale: rebuilds and re-applies read Postgres one page at a time; the periodic re-apply only
 * sends what changed since its last run; the country board is split into slices (one Redis Cluster
 * slot each) and its bookkeeping is compact and deleted once the tournament is final. See
 * {@link CountryBoardLayout}.
 *
 * <p>Each script touches one key, or keys sharing a hash tag, so this runs unchanged on a Redis
 * Cluster. The tests run Redis in cluster mode, which rejects cross-slot commands.
 */
@Component
public class LeaderboardWriter {

    private static final Logger log = LoggerFactory.getLogger(LeaderboardWriter.class);
    private static final int PAGE = 500;
    private static final int APPLY_BATCH = 500;
    private static final Duration REBUILD_LOCK_TTL = Duration.ofSeconds(60);
    /** Shorter than the re-apply interval, so each interval exactly one instance does the work. */
    private static final Duration REAPPLY_LOCK_TTL = Duration.ofMinutes(4);
    /** Covers clock differences between instances and commits that land after their timestamp. */
    private static final Duration REAPPLY_MARGIN = Duration.ofMinutes(1);
    private static final int MAX_MISSED_GROUPS = 10_000;
    private static final RedisScript<Long> RELEASE_LOCK = RedisScript.of(
            "if redis.call('GET', KEYS[1]) == ARGV[1] then return redis.call('DEL', KEYS[1]) end return 0",
            Long.class);

    private final StringRedisTemplate redis;
    private final GroupMemberRepository members;
    private final TournamentRepository tournaments;
    private final TournamentWindows windows;
    private final CountryBoardLayout layout;
    private final GameProperties props;
    private final TimeSource time;
    private final TransactionTemplate readTx;
    private final TransactionTemplate writeTx;
    private final Counter failures;
    private final Counter repairs;
    private final Counter countryRebuilds;
    private final String allCountries;
    /** Groups whose Redis write failed after commit (group id -> tournament id), retried from Postgres. */
    private final ConcurrentMap<Long, Long> missedGroups = new ConcurrentHashMap<>();
    private final RedisScript<Long> upsertGroupScript =
            RedisScript.of(new ClassPathResource("redis/upsert_group.lua"), Long.class);
    private final RedisScript<Long> countryApplyScript =
            RedisScript.of(new ClassPathResource("redis/country_apply.lua"), Long.class);

    /** A player's absolute score on the country board. */
    private record Contribution(long userId, String country, int score) {
    }

    public LeaderboardWriter(StringRedisTemplate redis, GroupMemberRepository members, TournamentRepository tournaments,
                             TournamentWindows windows, CountryBoardLayout layout, GameProperties props,
                             TimeSource time, PlatformTransactionManager txManager, MeterRegistry meters) {
        this.redis = redis;
        this.members = members;
        this.tournaments = tournaments;
        this.windows = windows;
        this.layout = layout;
        this.props = props;
        this.time = time;
        this.allCountries = String.join(",", props.countries());
        // REQUIRED on purpose. In an after-commit listener the request's connection is still held until
        // Spring cleans up, and these reads reuse it. REQUIRES_NEW would take a second pooled
        // connection per request: under a burst of joins every connection is held by a request
        // waiting for a second one, and the pool deadlocks (the 200-thread test caught this).
        this.readTx = new TransactionTemplate(txManager);
        this.readTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
        this.readTx.setReadOnly(true);
        // The finalization marker is written after the end-of-day commit and from the background
        // job, never from request bursts, so its own transaction (a second connection) is fine.
        this.writeTx = new TransactionTemplate(txManager);
        this.writeTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.failures = Counter.builder("leaderboard.redis.write.failures")
                .description("Redis leaderboard writes that failed after the Postgres commit")
                .register(meters);
        this.repairs = Counter.builder("leaderboard.redis.write.repairs")
                .description("Groups whose failed Redis write was re-applied from Postgres")
                .register(meters);
        this.countryRebuilds = Counter.builder("leaderboard.country.rebuilds")
                .description("Country boards rebuilt from Postgres (start of a tournament, Redis data loss, 20:00)")
                .register(meters);
    }

    // ---------------------------------------------------------------- live updates (after commit)

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onGroupActivated(GroupActivated event) {
        guarded("group activation " + event.groupId(), () -> {
            List<String> args = new ArrayList<>();
            args.add(Long.toString(expireAt(event.tournamentEnd())));
            for (LeaderboardEvents.Member m : event.members()) {
                args.add(Long.toString(LeaderboardScore.encode(0, m.joinedAt(), event.tournamentStart())));
                args.add(Long.toString(m.userId()));
            }
            redis.execute(upsertGroupScript, List.of(LeaderboardKeys.group(event.groupId())), args.toArray());

            List<Contribution> zeros = event.members().stream()
                    .map(m -> new Contribution(m.userId(), m.country(), 0)).toList();
            if (!applyToCountryBoard(event.tournamentId(), event.tournamentEnd(), zeros)) {
                rebuildBoards(event.tournamentId(), false);
            }
        }, () -> rememberMissed(event.groupId(), event.tournamentId()));
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onScoreChanged(ScoreChanged event) {
        guarded("score update for user " + event.userId(), () -> {
            long value = LeaderboardScore.encode(event.score(), event.reachedAt(), event.tournamentStart());
            redis.execute(upsertGroupScript, List.of(LeaderboardKeys.group(event.groupId())),
                    Long.toString(expireAt(event.tournamentEnd())), Long.toString(value),
                    Long.toString(event.userId()));

            Contribution c = new Contribution(event.userId(), event.country(), event.score());
            if (!applyToCountryBoard(event.tournamentId(), event.tournamentEnd(), List.of(c))) {
                rebuildBoards(event.tournamentId(), false);
            }
        }, () -> rememberMissed(event.groupId(), event.tournamentId()));
    }

    /**
     * After 20:00 nothing changes any more; make sure every board holds the final values. If this
     * fails, the tournament stays unfinalized in Postgres and {@link #reapplyTournaments()} retries.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onTournamentEnded(TournamentEnded event) {
        guarded("final rebuild of tournament " + event.tournamentId(), () -> finalizeBoards(event.tournamentId()));
    }

    // ---------------------------------------------------------------- repair

    /**
     * Re-applies, from Postgres, every group whose Redis write failed after its commit. Runs every
     * few seconds; stops at the first failure (Redis is probably still down) and tries again later.
     * Returns the number of groups repaired.
     */
    public int retryMissedWrites() {
        int repaired = 0;
        for (Map.Entry<Long, Long> missed : List.copyOf(missedGroups.entrySet())) {
            long groupId = missed.getKey();
            long tournamentId = missed.getValue();
            try {
                TournamentWindows.Window window = windows.of(tournamentId);
                List<MemberScore> groupMembers = readTx.execute(status ->
                        members.findRanked(groupId).stream().map(MemberScore::of).toList());
                upsertGroups(groupMembers, window);
                if (!applyToCountryBoard(tournamentId, window.endsAt(), contributions(groupMembers))) {
                    rebuildBoards(tournamentId, false);
                }
            } catch (RuntimeException ex) {
                log.debug("Retry of group {} failed, will try again: {}", groupId, ex.getMessage());
                break;
            }
            missedGroups.remove(groupId, tournamentId);
            repairs.increment();
            repaired++;
        }
        if (repaired > 0) {
            log.info("Repaired {} leaderboard groups whose Redis write had failed", repaired);
        }
        return repaired;
    }

    /**
     * Safety net for writes lost without anyone seeing an error, e.g. an instance that died
     * between its Postgres commit and its Redis write.
     *
     * <ul>
     *   <li>Open tournaments: re-applies the scores that changed (and groups that filled) since the
     *       previous run, plus a minute of margin. Only those travel to Redis; the first run, or
     *       one after Redis lost its data, does everything. One instance per interval does it.</li>
     *   <li>Finished tournaments whose final rebuild never succeeded: retried until it does, while
     *       the boards would still exist. Never the whole history.</li>
     * </ul>
     * Returns the number of tournaments handled.
     */
    public int reapplyTournaments() {
        int done = 0;
        Instant boardsStillLive = time.now().minus(props.leaderboard().ttlAfterEnd());
        for (Tournament t : tournaments.findByStatusAndBoardsFinalizedAtIsNullAndEndsAtAfter(
                Tournament.Status.ENDED, boardsStillLive)) {
            try {
                if (finalizeBoards(t.getId())) {
                    log.info("Finished the final leaderboard rebuild of tournament {} on retry", t.getId());
                    done++;
                }
            } catch (RuntimeException ex) {
                log.warn("Final leaderboard rebuild of tournament {} failed again, will retry: {}",
                        t.getId(), ex.getMessage());
            }
        }
        for (Tournament t : tournaments.findByStatus(Tournament.Status.ACTIVE)) {
            long tournamentId = t.getId();
            if (!Boolean.TRUE.equals(redis.opsForValue()
                    .setIfAbsent(LeaderboardKeys.reapplyLock(tournamentId), "1", REAPPLY_LOCK_TTL))) {
                continue;
            }
            Instant runStart = time.now();
            String last = redis.opsForValue().get(LeaderboardKeys.reappliedAt(tournamentId));
            TournamentWindows.Window window = windows.of(tournamentId);
            if (last == null || !countryBoardComplete(tournamentId)) {
                rebuildBoards(tournamentId, true);
            } else {
                Instant since = Instant.ofEpochMilli(Long.parseLong(last)).minus(REAPPLY_MARGIN);
                boolean[] complete = {true};
                forEachStartedPage(tournamentId, since, page -> {
                    upsertGroups(page, window);
                    complete[0] &= applyToCountryBoard(tournamentId, window.endsAt(), contributions(page));
                });
                if (!complete[0]) {
                    rebuildBoards(tournamentId, false);
                }
            }
            String key = LeaderboardKeys.reappliedAt(tournamentId);
            redis.opsForValue().set(key, Long.toString(runStart.toEpochMilli()));
            redis.expireAt(key, Instant.ofEpochSecond(expireAt(window.endsAt())));
            done++;
        }
        return done;
    }

    /** Used at startup: an incomplete country board means Redis lost data, so rebuild everything. */
    public void rebuildIfMissing(long tournamentId) {
        guarded("startup rebuild of tournament " + tournamentId, () -> {
            if (!countryBoardComplete(tournamentId)) {
                rebuildBoards(tournamentId, true);
            }
        });
    }

    /** Rebuilds all of a tournament's boards from Postgres. False if another instance is already at it. */
    public boolean rebuild(long tournamentId) {
        return rebuildBoards(tournamentId, true) != null;
    }

    /** Re-adds a group's members with their Postgres values. Safe to race with live updates (GT). */
    public void healGroup(long groupId, long tournamentId, List<MemberScore> groupMembers) {
        guarded("heal of group " + groupId, () -> upsertGroups(groupMembers, windows.of(tournamentId)));
    }

    /** True if every slice of the tournament's country board is complete. */
    public boolean countryBoardComplete(long tournamentId) {
        for (int shard = 0; shard < layout.shards(); shard++) {
            if (!"1".equals(redis.opsForHash().get(layout.meta(tournamentId, shard), "ready"))) {
                return false;
            }
        }
        return true;
    }

    // ---------------------------------------------------------------- rebuild and finalization

    /**
     * Rebuilds the country board (and the group boards if {@code withGroups}) from Postgres, one
     * page at a time, then marks every slice complete. Every update committed before a page was
     * read is in it, and every later one applies itself, so the result is exact. One rebuild per
     * tournament at a time; returns null if another instance holds the lock, otherwise the
     * country-board buckets in use (shard -> bucket ids), which finalization deletes.
     */
    private Map<Integer, Set<Long>> rebuildBoards(long tournamentId, boolean withGroups) {
        String lock = LeaderboardKeys.countryRebuildLock(tournamentId);
        String token = UUID.randomUUID().toString();
        if (!Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(lock, token, REBUILD_LOCK_TTL))) {
            return null;
        }
        try {
            TournamentWindows.Window window = windows.of(tournamentId);
            Map<Integer, Set<Long>> buckets = new HashMap<>();
            int[] players = {0};
            forEachStartedPage(tournamentId, null, page -> {
                if (withGroups) {
                    upsertGroups(page, window);
                }
                applyToCountryBoard(tournamentId, window.endsAt(), contributions(page));
                for (MemberScore m : page) {
                    buckets.computeIfAbsent(layout.shard(m.userId()), s -> new HashSet<>()).add(layout.bucket(m.userId()));
                }
                players[0] += page.size();
            });
            if (players[0] == 0) {
                // No full group yet, so no board. Marking an empty board complete would only be
                // thrown away by the first group's update.
                return buckets;
            }
            countryRebuilds.increment();
            for (int shard = 0; shard < layout.shards(); shard++) {
                applyToShard(tournamentId, shard, window.endsAt(), List.of(), true);
            }
            log.info("Rebuilt leaderboards of tournament {} from {} players", tournamentId, players[0]);
            return buckets;
        } finally {
            redis.execute(RELEASE_LOCK, List.of(lock), token);
        }
    }

    /**
     * Rebuilds a finished tournament's boards, records that in Postgres, then deletes the country
     * board's bookkeeping: once scores can't change it is dead weight, so it lives for the
     * tournament's day instead of the boards' two-day TTL. Returns false (and records nothing) if
     * another instance is rebuilding right now.
     */
    private boolean finalizeBoards(long tournamentId) {
        Map<Integer, Set<Long>> buckets = rebuildBoards(tournamentId, true);
        if (buckets == null) {
            return false;
        }
        writeTx.executeWithoutResult(status -> tournaments.markBoardsFinalized(tournamentId, time.now()));
        if (buckets.isEmpty()) {
            return true; // no full group ever: no board to finalize
        }
        try {
            Instant expiry = Instant.ofEpochSecond(expireAt(windows.of(tournamentId).endsAt()));
            for (int shard = 0; shard < layout.shards(); shard++) {
                // Final first: from now on the slice ignores updates, so nothing touches the buckets.
                String meta = layout.meta(tournamentId, shard);
                redis.opsForHash().put(meta, "final", "1");
                redis.expireAt(meta, expiry);
            }
            buckets.forEach((shard, ids) -> {
                List<String> keys = ids.stream().map(b -> layout.bucketKey(tournamentId, shard, b)).toList();
                for (int from = 0; from < keys.size(); from += APPLY_BATCH) {
                    redis.delete(keys.subList(from, Math.min(from + APPLY_BATCH, keys.size())));
                }
            });
        } catch (RuntimeException ex) {
            // The board is final and exact; the leftover bookkeeping just expires with its TTL.
            log.warn("Could not delete the country board bookkeeping of tournament {}: {}", tournamentId, ex.getMessage());
        }
        return true;
    }

    // ---------------------------------------------------------------- Redis writes

    private void upsertGroups(List<MemberScore> scores, TournamentWindows.Window window) {
        Map<Long, List<MemberScore>> byGroup = scores.stream()
                .collect(Collectors.groupingBy(MemberScore::groupId, LinkedHashMap::new, Collectors.toList()));
        byGroup.forEach((groupId, groupMembers) -> {
            List<String> args = new ArrayList<>();
            args.add(Long.toString(expireAt(window.endsAt())));
            for (MemberScore m : groupMembers) {
                Instant reachedAt = m.scoreUpdatedAt() != null ? m.scoreUpdatedAt() : window.startsAt();
                args.add(Long.toString(LeaderboardScore.encode(m.score(), reachedAt, window.startsAt())));
                args.add(Long.toString(m.userId()));
            }
            redis.execute(upsertGroupScript, List.of(LeaderboardKeys.group(groupId)), args.toArray());
        });
    }

    /** Applies contributions slice by slice. True if every slice touched is complete. */
    private boolean applyToCountryBoard(long tournamentId, Instant tournamentEnd, List<Contribution> contributions) {
        Map<Integer, List<Contribution>> byShard = contributions.stream()
                .collect(Collectors.groupingBy(c -> layout.shard(c.userId()), TreeMap::new, Collectors.toList()));
        boolean complete = true;
        for (Map.Entry<Integer, List<Contribution>> shard : byShard.entrySet()) {
            List<Contribution> all = shard.getValue();
            for (int from = 0; from < all.size(); from += APPLY_BATCH) {
                complete &= applyToShard(tournamentId, shard.getKey(), tournamentEnd,
                        all.subList(from, Math.min(from + APPLY_BATCH, all.size())), false);
            }
        }
        return complete;
    }

    private boolean applyToShard(long tournamentId, int shard, Instant tournamentEnd, List<Contribution> contributions,
                                 boolean markComplete) {
        Map<Long, Integer> bucketPosition = new LinkedHashMap<>();
        for (Contribution c : contributions) {
            bucketPosition.putIfAbsent(layout.bucket(c.userId()), bucketPosition.size() + 1);
        }
        List<String> keys = new ArrayList<>(2 + bucketPosition.size());
        keys.add(layout.totals(tournamentId, shard));
        keys.add(layout.meta(tournamentId, shard));
        List<String> args = new ArrayList<>(4 + bucketPosition.size() + 4 * contributions.size());
        args.add(Long.toString(expireAt(tournamentEnd)));
        args.add(markComplete ? "1" : "0");
        args.add(markComplete ? allCountries : "");
        args.add(Integer.toString(bucketPosition.size()));
        for (long bucket : bucketPosition.keySet()) {
            keys.add(layout.bucketKey(tournamentId, shard, bucket));
            args.add(Long.toString(bucket));
        }
        for (Contribution c : contributions) {
            args.add(Integer.toString(bucketPosition.get(layout.bucket(c.userId()))));
            args.add(Long.toString(c.userId()));
            args.add(c.country());
            args.add(Integer.toString(c.score()));
        }
        Long complete = redis.execute(countryApplyScript, keys, args.toArray());
        return complete != null && complete == 1L;
    }

    // ---------------------------------------------------------------- helpers

    /** Feeds members of full groups to {@code page}, 500 at a time; {@code since} null means all. */
    private void forEachStartedPage(long tournamentId, Instant since, Consumer<List<MemberScore>> page) {
        long after = 0;
        List<MemberScore> batch;
        do {
            long from = after;
            batch = readTx.execute(status -> since == null
                    ? members.findStartedPage(tournamentId, from, PageRequest.of(0, PAGE))
                    : members.findStartedChangedPage(tournamentId, since, from, PageRequest.of(0, PAGE)));
            if (batch == null || batch.isEmpty()) {
                return;
            }
            page.accept(batch);
            after = batch.get(batch.size() - 1).id();
        } while (batch.size() == PAGE);
    }

    private void rememberMissed(long groupId, long tournamentId) {
        // Past the cap Redis has been down for a while; the periodic re-apply covers everything.
        if (missedGroups.size() < MAX_MISSED_GROUPS) {
            missedGroups.put(groupId, tournamentId);
        }
    }

    private static List<Contribution> contributions(List<MemberScore> scores) {
        return scores.stream().map(m -> new Contribution(m.userId(), m.country(), m.score())).toList();
    }

    private long expireAt(Instant tournamentEnd) {
        return tournamentEnd.plus(props.leaderboard().ttlAfterEnd()).getEpochSecond();
    }

    private void guarded(String what, Runnable action) {
        guarded(what, action, () -> { });
    }

    private void guarded(String what, Runnable action, Runnable onFailure) {
        try {
            action.run();
        } catch (RuntimeException ex) {
            failures.increment();
            onFailure.run();
            log.warn("Redis leaderboard write failed ({}); Postgres is unaffected, will retry from it: {}",
                    what, ex.getMessage());
        }
    }
}
