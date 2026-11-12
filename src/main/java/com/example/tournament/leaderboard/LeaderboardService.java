package com.example.tournament.leaderboard;

import com.example.tournament.common.ApiException;
import com.example.tournament.common.ErrorCode;
import com.example.tournament.config.GameProperties;
import com.example.tournament.tournament.GroupMember;
import com.example.tournament.tournament.GroupMemberRepository;
import com.example.tournament.tournament.MemberScore;
import com.example.tournament.tournament.TournamentGroup;
import com.example.tournament.tournament.TournamentGroupRepository;
import com.example.tournament.tournament.TournamentRepository;
import com.example.tournament.user.User;
import com.example.tournament.user.UserRepository;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations.TypedTuple;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

/**
 * Reads come from Redis. If Redis is down, or a board is missing or incomplete (group still
 * WAITING, key expired, Redis wiped), the answer comes from Postgres instead, and an ACTIVE
 * group's board is written back so the next read is served from Redis again.
 */
@Service
public class LeaderboardService {

    private static final Logger log = LoggerFactory.getLogger(LeaderboardService.class);

    private final StringRedisTemplate redis;
    private final UserRepository users;
    private final GroupMemberRepository members;
    private final TournamentGroupRepository groups;
    private final TournamentRepository tournaments;
    private final LeaderboardWriter writer;
    private final GameProperties props;
    private final CountryBoardLayout layout;
    private final long countryCacheNanos;
    private final ConcurrentMap<Long, CachedBoard> countryCache = new ConcurrentHashMap<>();

    private record CachedBoard(List<CountryScore> board, long readAtNanos) {
    }
    @SuppressWarnings("rawtypes")
    private final RedisScript<List> countryReadScript =
            RedisScript.of(new ClassPathResource("redis/country_read.lua"), List.class);

    public LeaderboardService(StringRedisTemplate redis, UserRepository users, GroupMemberRepository members,
                              TournamentGroupRepository groups, TournamentRepository tournaments,
                              LeaderboardWriter writer, GameProperties props, CountryBoardLayout layout) {
        this.redis = redis;
        this.users = users;
        this.members = members;
        this.groups = groups;
        this.tournaments = tournaments;
        this.writer = writer;
        this.props = props;
        this.layout = layout;
        this.countryCacheNanos = props.leaderboard().countryBoardCache().toNanos();
    }

    /**
     * Redis orders equal values by member string (so "15" before "14" in ZREVRANGE). Postgres
     * breaks exact ties by the lower user id. Group boards hold five entries, so the read path
     * simply re-sorts them the Postgres way: the live board, the rank endpoint and the 20:00
     * ranking always agree.
     */
    private static final Comparator<TypedTuple<String>> RANKING_ORDER =
            Comparator.comparing((TypedTuple<String> t) -> t.getScore(), Comparator.reverseOrder())
                    .thenComparingLong(t -> Long.parseLong(t.getValue()));

    /** The group board from Redis in ranking order, or null if Redis can't answer for all members. */
    private List<TypedTuple<String>> rankedFromRedis(long groupId) {
        Set<TypedTuple<String>> board =
                fromRedis(() -> redis.opsForZSet().reverseRangeWithScores(LeaderboardKeys.group(groupId), 0, -1));
        if (board == null || board.size() < props.groupSize()) {
            return null;
        }
        return board.stream().sorted(RANKING_ORDER).toList();
    }

    public List<LeaderboardEntry> groupLeaderboard(long groupId) {
        List<TypedTuple<String>> board = rankedFromRedis(groupId);
        if (board != null) {
            List<Long> ids = board.stream().map(t -> Long.parseLong(t.getValue())).toList();
            Map<Long, User> byId = usersById(ids);
            return board.stream().map(t -> {
                User u = byId.get(Long.parseLong(t.getValue()));
                return new LeaderboardEntry(u.getId(), u.getUsername(), u.getCountry(),
                        LeaderboardScore.decode(t.getScore()));
            }).toList();
        }

        TournamentGroup group = groups.findById(groupId)
                .orElseThrow(() -> new ApiException(ErrorCode.GROUP_NOT_FOUND));
        List<GroupMember> ranked = members.findRanked(groupId);
        if (group.getStatus() == TournamentGroup.Status.ACTIVE) {
            writer.healGroup(groupId, group.getTournamentId(), ranked.stream().map(MemberScore::of).toList());
        }
        return entries(ranked);
    }

    /** 1-based rank of the user inside their group. */
    public RankResponse rank(long tournamentId, long userId) {
        GroupMember member = members.findByTournamentIdAndUserId(tournamentId, userId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_IN_TOURNAMENT));
        // Not ZREVRANK: its tie order differs from the ranking (see RANKING_ORDER).
        List<TypedTuple<String>> board = rankedFromRedis(member.getGroupId());
        if (board != null) {
            String self = Long.toString(userId);
            for (int i = 0; i < board.size(); i++) {
                if (self.equals(board.get(i).getValue())) {
                    return new RankResponse(i + 1);
                }
            }
        }

        List<GroupMember> ranked = members.findRanked(member.getGroupId());
        for (int i = 0; i < ranked.size(); i++) {
            if (ranked.get(i).getUserId() == userId) {
                return new RankResponse(i + 1);
            }
        }
        throw new ApiException(ErrorCode.NOT_IN_TOURNAMENT);
    }

    private static final Comparator<CountryScore> COUNTRY_ORDER = Comparator.comparingLong(CountryScore::score)
            .reversed().thenComparing(CountryScore::country, Comparator.reverseOrder());

    /**
     * The sum of the board's slices, from Redis only when every slice is complete; while any is
     * missing or being rebuilt, from Postgres. Reused for up to game.leaderboard.country-board-cache
     * (1 s): one total for the whole tournament, read by everyone, costing one call per slice.
     */
    public List<CountryScore> countryLeaderboard(long tournamentId) {
        if (countryCacheNanos > 0) {
            CachedBoard cached = countryCache.get(tournamentId);
            if (cached != null && System.nanoTime() - cached.readAtNanos() < countryCacheNanos) {
                return cached.board();
            }
        }
        List<CountryScore> board = readCountryBoard(tournamentId);
        if (countryCacheNanos > 0) {
            countryCache.put(tournamentId, new CachedBoard(board, System.nanoTime()));
        }
        return board;
    }

    @SuppressWarnings("unchecked")
    private List<CountryScore> readCountryBoard(long tournamentId) {
        Map<String, Long> totals = new HashMap<>();
        boolean fromRedis = true;
        for (int shard = 0; shard < layout.shards() && fromRedis; shard++) {
            int s = shard;
            List<String> flat = fromRedis(() -> (List<String>) redis.execute(countryReadScript,
                    List.of(layout.totals(tournamentId, s), layout.meta(tournamentId, s))));
            if (flat == null || flat.isEmpty()) {
                fromRedis = false;
                break;
            }
            for (int i = 0; i + 1 < flat.size(); i += 2) {
                totals.merge(flat.get(i), (long) Double.parseDouble(flat.get(i + 1)), Long::sum);
            }
        }
        if (fromRedis) {
            return totals.entrySet().stream()
                    .map(e -> new CountryScore(e.getKey(), e.getValue()))
                    .sorted(COUNTRY_ORDER)
                    .toList();
        }
        if (!tournaments.existsById(tournamentId)) {
            throw new ApiException(ErrorCode.TOURNAMENT_NOT_FOUND);
        }
        return members.countryTotals(tournamentId).stream()
                .map(row -> new CountryScore((String) row[0], ((Number) row[1]).longValue()))
                .sorted(COUNTRY_ORDER)
                .toList();
    }

    /** Postgres members (already in rank order) to response rows, with one query for the names. */
    public List<LeaderboardEntry> entries(List<GroupMember> rankedMembers) {
        Map<Long, User> byId = usersById(rankedMembers.stream().map(GroupMember::getUserId).toList());
        return rankedMembers.stream().map(m -> {
            User u = byId.get(m.getUserId());
            return new LeaderboardEntry(m.getUserId(), u.getUsername(), m.getCountry(), m.getScore());
        }).toList();
    }

    private Map<Long, User> usersById(List<Long> ids) {
        return users.findAllById(ids).stream().collect(Collectors.toMap(User::getId, Function.identity()));
    }

    private <T> T fromRedis(Supplier<T> read) {
        try {
            return read.get();
        } catch (DataAccessException ex) {
            log.warn("Redis read failed, answering from Postgres: {}", ex.getMessage());
            return null;
        }
    }
}
