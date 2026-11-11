package com.example.tournament.leaderboard;

import com.example.tournament.config.GameProperties;
import com.example.tournament.leaderboard.LeaderboardEvents.GroupActivated;
import com.example.tournament.leaderboard.LeaderboardEvents.ScoreChanged;
import com.example.tournament.leaderboard.LeaderboardEvents.TournamentEnded;
import com.example.tournament.tournament.GroupMember;
import com.example.tournament.tournament.GroupMemberRepository;
import com.example.tournament.tournament.TournamentWindows;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
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
 * Redis write is logged and counted, never surfaced to the player, and the boards can always be
 * rebuilt from group_members.
 *
 * <p>Every write is idempotent. Group boards keep each player's highest value (ZADD GT). The
 * country board records the highest score it has counted per player and adds only the
 * difference. Late, repeated or out-of-order updates, and rebuilds running alongside live
 * traffic, can't count a point twice or lose one.
 *
 * <p>Each script touches one key, or keys sharing a hash tag, so this runs unchanged on a
 * Redis Cluster. The tests run Redis in cluster mode, which rejects cross-slot commands.
 */
@Component
public class LeaderboardWriter {

    private static final Logger log = LoggerFactory.getLogger(LeaderboardWriter.class);
    private static final int APPLY_BATCH = 500;
    private static final Duration REBUILD_LOCK_TTL = Duration.ofSeconds(60);
    private static final RedisScript<Long> RELEASE_LOCK = RedisScript.of(
            "if redis.call('GET', KEYS[1]) == ARGV[1] then return redis.call('DEL', KEYS[1]) end return 0",
            Long.class);

    private final StringRedisTemplate redis;
    private final GroupMemberRepository members;
    private final TournamentWindows windows;
    private final GameProperties props;
    private final TransactionTemplate readTx;
    private final Counter failures;
    private final RedisScript<Long> upsertGroupScript =
            RedisScript.of(new ClassPathResource("redis/upsert_group.lua"), Long.class);
    private final RedisScript<Long> countryApplyScript =
            RedisScript.of(new ClassPathResource("redis/country_apply.lua"), Long.class);

    /** A player's absolute score on the country board. */
    private record Contribution(long userId, String country, int score) {
    }

    public LeaderboardWriter(StringRedisTemplate redis, GroupMemberRepository members, TournamentWindows windows,
                             GameProperties props, PlatformTransactionManager txManager, MeterRegistry meters) {
        this.redis = redis;
        this.members = members;
        this.windows = windows;
        this.props = props;
        // REQUIRED on purpose. In an after-commit listener the request's connection is still held until
        // Spring cleans up, and these reads reuse it. REQUIRES_NEW would take a second pooled
        // connection per request: under a burst of joins every connection is held by a request
        // waiting for a second one, and the pool deadlocks (the 200-thread test caught this).
        this.readTx = new TransactionTemplate(txManager);
        this.readTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
        this.readTx.setReadOnly(true);
        this.failures = Counter.builder("leaderboard.redis.write.failures")
                .description("Redis leaderboard writes that failed after the Postgres commit")
                .register(meters);
    }

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
            if (!applyToCountryBoard(event.tournamentId(), event.tournamentEnd(), zeros, false)) {
                rebuildCountryBoard(event.tournamentId());
            }
        });
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onScoreChanged(ScoreChanged event) {
        guarded("score update for user " + event.userId(), () -> {
            long value = LeaderboardScore.encode(event.score(), event.reachedAt(), event.tournamentStart());
            redis.execute(upsertGroupScript, List.of(LeaderboardKeys.group(event.groupId())),
                    Long.toString(expireAt(event.tournamentEnd())), Long.toString(value),
                    Long.toString(event.userId()));

            Contribution c = new Contribution(event.userId(), event.country(), event.score());
            if (!applyToCountryBoard(event.tournamentId(), event.tournamentEnd(), List.of(c), false)) {
                rebuildCountryBoard(event.tournamentId());
            }
        });
    }

    /** After 20:00 nothing changes any more; make sure every board holds the final values. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onTournamentEnded(TournamentEnded event) {
        guarded("final rebuild of tournament " + event.tournamentId(), () -> rebuild(event.tournamentId()));
    }

    /** Rebuilds every board of a tournament from Postgres. Safe to run while live updates arrive. */
    public void rebuild(long tournamentId) {
        TournamentWindows.Window window = windows.of(tournamentId);
        List<GroupMember> started = readTx.execute(status -> members.findInStartedGroups(tournamentId));
        Map<Long, List<GroupMember>> byGroup = started.stream()
                .collect(Collectors.groupingBy(GroupMember::getGroupId, LinkedHashMap::new, Collectors.toList()));
        byGroup.forEach((groupId, groupMembers) -> upsertGroup(groupId, groupMembers, window));
        rebuildCountryBoard(tournamentId);
        log.info("Rebuilt leaderboards of tournament {} ({} groups)", tournamentId, byGroup.size());
    }

    /** Used at startup: an incomplete country board means Redis lost data, so rebuild everything. */
    public void rebuildIfMissing(long tournamentId) {
        guarded("startup rebuild of tournament " + tournamentId, () -> {
            if (!Boolean.TRUE.equals(redis.opsForHash().hasKey(LeaderboardKeys.countryApplied(tournamentId), "_ready"))) {
                rebuild(tournamentId);
            }
        });
    }

    /** Re-adds a group's members with their Postgres values. Safe to race with live updates (GT). */
    public void healGroup(long groupId, long tournamentId, List<GroupMember> groupMembers) {
        guarded("heal of group " + groupId, () -> upsertGroup(groupId, groupMembers, windows.of(tournamentId)));
    }

    private void upsertGroup(long groupId, List<GroupMember> groupMembers, TournamentWindows.Window window) {
        List<String> args = new ArrayList<>();
        args.add(Long.toString(expireAt(window.endsAt())));
        for (GroupMember m : groupMembers) {
            Instant reachedAt = m.getScoreUpdatedAt() != null ? m.getScoreUpdatedAt() : window.startsAt();
            args.add(Long.toString(LeaderboardScore.encode(m.getScore(), reachedAt, window.startsAt())));
            args.add(Long.toString(m.getUserId()));
        }
        redis.execute(upsertGroupScript, List.of(LeaderboardKeys.group(groupId)), args.toArray());
    }

    /** Returns true if the country board is complete, false if it needs a rebuild. */
    private boolean applyToCountryBoard(long tournamentId, Instant tournamentEnd, List<Contribution> contributions,
                                        boolean markComplete) {
        List<String> args = new ArrayList<>(2 + 3 * contributions.size());
        args.add(Long.toString(expireAt(tournamentEnd)));
        args.add(markComplete ? "1" : "0");
        for (Contribution c : contributions) {
            args.add(Long.toString(c.userId()));
            args.add(c.country());
            args.add(Integer.toString(c.score()));
        }
        Long complete = redis.execute(countryApplyScript,
                List.of(LeaderboardKeys.country(tournamentId), LeaderboardKeys.countryApplied(tournamentId)),
                args.toArray());
        return complete != null && complete == 1L;
    }

    /**
     * Replays every player's Postgres score into the country board, then marks it complete. Until
     * then reads come from Postgres. Every update committed before the Postgres read is in the
     * replay, and every later one applies itself after its own commit, so the result is exact.
     * One rebuild per tournament at a time: if another instance holds the lock, this returns and
     * leaves the work to it.
     */
    private void rebuildCountryBoard(long tournamentId) {
        String lock = LeaderboardKeys.countryRebuildLock(tournamentId);
        String token = UUID.randomUUID().toString();
        if (!Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(lock, token, REBUILD_LOCK_TTL))) {
            return;
        }
        try {
            Instant end = windows.of(tournamentId).endsAt();
            List<Contribution> all = readTx.execute(status -> members.findInStartedGroups(tournamentId)).stream()
                    .map(m -> new Contribution(m.getUserId(), m.getCountry(), m.getScore()))
                    .toList();
            for (int from = 0; from < all.size(); from += APPLY_BATCH) {
                applyToCountryBoard(tournamentId, end, all.subList(from, Math.min(from + APPLY_BATCH, all.size())), false);
            }
            applyToCountryBoard(tournamentId, end, List.of(), true);
            log.info("Rebuilt country board of tournament {} from {} players", tournamentId, all.size());
        } finally {
            redis.execute(RELEASE_LOCK, List.of(lock), token);
        }
    }

    private long expireAt(Instant tournamentEnd) {
        return tournamentEnd.plus(props.leaderboard().ttlAfterEnd()).getEpochSecond();
    }

    private void guarded(String what, Runnable action) {
        try {
            action.run();
        } catch (RuntimeException ex) {
            failures.increment();
            log.warn("Redis leaderboard write failed ({}); Postgres is unaffected", what, ex);
        }
    }
}
