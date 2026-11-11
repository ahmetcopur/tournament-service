package com.example.tournament.leaderboard;

import com.example.tournament.common.ApiException;
import com.example.tournament.common.ErrorCode;
import com.example.tournament.config.GameProperties;
import com.example.tournament.tournament.GroupMember;
import com.example.tournament.tournament.GroupMemberRepository;
import com.example.tournament.tournament.TournamentGroup;
import com.example.tournament.tournament.TournamentGroupRepository;
import com.example.tournament.tournament.TournamentRepository;
import com.example.tournament.user.User;
import com.example.tournament.user.UserRepository;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations.TypedTuple;
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

    public LeaderboardService(StringRedisTemplate redis, UserRepository users, GroupMemberRepository members,
                              TournamentGroupRepository groups, TournamentRepository tournaments,
                              LeaderboardWriter writer, GameProperties props) {
        this.redis = redis;
        this.users = users;
        this.members = members;
        this.groups = groups;
        this.tournaments = tournaments;
        this.writer = writer;
        this.props = props;
    }

    public List<LeaderboardEntry> groupLeaderboard(long groupId) {
        Set<TypedTuple<String>> board =
                fromRedis(() -> redis.opsForZSet().reverseRangeWithScores(LeaderboardKeys.group(groupId), 0, -1));
        if (board != null && board.size() >= props.groupSize()) {
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
            writer.healGroup(groupId, group.getTournamentId(), ranked);
        }
        return entries(ranked);
    }

    /** 1-based rank of the user inside their group. */
    public RankResponse rank(long tournamentId, long userId) {
        GroupMember member = members.findByTournamentIdAndUserId(tournamentId, userId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_IN_TOURNAMENT));
        String key = LeaderboardKeys.group(member.getGroupId());
        byte[] rawKey = key.getBytes();
        byte[] rawMember = Long.toString(userId).getBytes();
        List<Object> replies = fromRedis(() -> redis.executePipelined((RedisCallback<Object>) (RedisConnection c) -> {
            c.zSetCommands().zCard(rawKey);
            c.zSetCommands().zRevRank(rawKey, rawMember);
            return null;
        }));
        if (replies != null && replies.size() == 2
                && replies.get(0) instanceof Long size && size >= props.groupSize()
                && replies.get(1) instanceof Long zeroBased) {
            return new RankResponse((int) (zeroBased + 1));
        }

        List<GroupMember> ranked = members.findRanked(member.getGroupId());
        for (int i = 0; i < ranked.size(); i++) {
            if (ranked.get(i).getUserId() == userId) {
                return new RankResponse(i + 1);
            }
        }
        throw new ApiException(ErrorCode.NOT_IN_TOURNAMENT);
    }

    public List<CountryScore> countryLeaderboard(long tournamentId) {
        Set<TypedTuple<String>> board =
                fromRedis(() -> redis.opsForZSet().reverseRangeWithScores(LeaderboardKeys.country(tournamentId), 0, -1));
        if (board != null && !board.isEmpty()) {
            return board.stream().map(t -> new CountryScore(t.getValue(), t.getScore().longValue())).toList();
        }
        if (!tournaments.existsById(tournamentId)) {
            throw new ApiException(ErrorCode.TOURNAMENT_NOT_FOUND);
        }
        return members.countryTotals(tournamentId).stream()
                .map(row -> new CountryScore((String) row[0], ((Number) row[1]).longValue()))
                .sorted(Comparator.comparingLong(CountryScore::score).reversed()
                        .thenComparing(CountryScore::country, Comparator.reverseOrder()))
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
