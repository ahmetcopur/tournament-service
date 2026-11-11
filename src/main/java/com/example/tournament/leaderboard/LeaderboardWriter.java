package com.example.tournament.leaderboard;

import com.example.tournament.config.GameProperties;
import com.example.tournament.leaderboard.LeaderboardEvents.GroupActivated;
import com.example.tournament.leaderboard.LeaderboardEvents.Member;
import com.example.tournament.leaderboard.LeaderboardEvents.ScoreChanged;
import com.example.tournament.leaderboard.LeaderboardEvents.TournamentEnded;
import com.example.tournament.tournament.GroupMember;
import com.example.tournament.tournament.GroupMemberRepository;
import com.example.tournament.tournament.TournamentWindows;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations.TypedTuple;
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
 */
@Component
public class LeaderboardWriter {

    private static final Logger log = LoggerFactory.getLogger(LeaderboardWriter.class);

    private final StringRedisTemplate redis;
    private final GroupMemberRepository members;
    private final TournamentWindows windows;
    private final GameProperties props;
    private final TransactionTemplate readTx;
    private final Counter failures;
    private final RedisScript<Long> levelUpScript =
            RedisScript.of(new ClassPathResource("redis/level_up.lua"), Long.class);
    private final RedisScript<Long> upsertGroupScript =
            RedisScript.of(new ClassPathResource("redis/upsert_group.lua"), Long.class);

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
            long expireAt = expireAt(event.tournamentEnd());
            List<String> args = new ArrayList<>();
            args.add(Long.toString(expireAt));
            for (Member m : event.members()) {
                args.add(Long.toString(LeaderboardScore.encode(0, m.joinedAt(), event.tournamentStart())));
                args.add(Long.toString(m.userId()));
            }
            redis.execute(upsertGroupScript, List.of(LeaderboardKeys.group(event.groupId())), args.toArray());
            if (!Boolean.TRUE.equals(redis.hasKey(LeaderboardKeys.country(event.tournamentId())))) {
                rebuildCountryBoard(event.tournamentId());
            }
        });
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onScoreChanged(ScoreChanged event) {
        guarded("score update for user " + event.userId(), () -> {
            long value = LeaderboardScore.encode(event.score(), event.reachedAt(), event.tournamentStart());
            Long countryUpdated = redis.execute(levelUpScript,
                    List.of(LeaderboardKeys.group(event.groupId()), LeaderboardKeys.country(event.tournamentId())),
                    Long.toString(value), Long.toString(event.userId()), event.country(),
                    Long.toString(expireAt(event.tournamentEnd())));
            if (countryUpdated != null && countryUpdated == 0L) {
                rebuildCountryBoard(event.tournamentId());
            }
        });
    }

    /** After 20:00 nothing changes any more, so the boards are rewritten with the exact final values. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onTournamentEnded(TournamentEnded event) {
        guarded("final rebuild of tournament " + event.tournamentId(), () -> rebuild(event.tournamentId()));
    }

    /** Rebuilds every board of a tournament from Postgres. */
    public void rebuild(long tournamentId) {
        TournamentWindows.Window window = windows.of(tournamentId);
        List<GroupMember> started = readTx.execute(status -> members.findInStartedGroups(tournamentId));
        Map<Long, List<GroupMember>> byGroup = started.stream()
                .collect(Collectors.groupingBy(GroupMember::getGroupId, LinkedHashMap::new, Collectors.toList()));
        byGroup.forEach((groupId, groupMembers) -> upsertGroup(groupId, groupMembers, window));
        rebuildCountryBoard(tournamentId);
        log.info("Rebuilt leaderboards of tournament {} ({} groups)", tournamentId, byGroup.size());
    }

    /** Used at startup: a missing country board means Redis was wiped, so rebuild everything. */
    public void rebuildIfMissing(long tournamentId) {
        guarded("startup rebuild of tournament " + tournamentId, () -> {
            if (!Boolean.TRUE.equals(redis.hasKey(LeaderboardKeys.country(tournamentId)))) {
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

    /**
     * Country totals only ever grow through ZINCRBY, which can't be replayed safely, so a missing
     * board is rewritten from Postgres sums. Increments in flight during a rebuild can be off by a
     * few points until the final rewrite at 20:00.
     */
    private void rebuildCountryBoard(long tournamentId) {
        List<Object[]> totals = readTx.execute(status -> members.countryTotals(tournamentId));
        if (totals == null || totals.isEmpty()) {
            return;
        }
        Set<TypedTuple<String>> tuples = totals.stream()
                .map(row -> TypedTuple.of((String) row[0], ((Number) row[1]).doubleValue()))
                .collect(Collectors.toSet());
        String key = LeaderboardKeys.country(tournamentId);
        redis.opsForZSet().add(key, tuples);
        redis.expireAt(key, Instant.ofEpochSecond(expireAt(windows.of(tournamentId).endsAt())));
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
