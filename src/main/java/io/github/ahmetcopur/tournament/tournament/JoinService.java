package io.github.ahmetcopur.tournament.tournament;

import io.github.ahmetcopur.tournament.common.ApiException;
import io.github.ahmetcopur.tournament.common.ErrorCode;
import io.github.ahmetcopur.tournament.config.GameProperties;
import io.github.ahmetcopur.tournament.config.TimeSource;
import io.github.ahmetcopur.tournament.leaderboard.LeaderboardEvents.GroupActivated;
import io.github.ahmetcopur.tournament.leaderboard.LeaderboardEvents.Member;
import io.github.ahmetcopur.tournament.leaderboard.LeaderboardService;
import io.github.ahmetcopur.tournament.user.User;
import io.github.ahmetcopur.tournament.user.UserRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Matchmaking, entirely in Postgres and in one transaction: checks, fee, group lookup, insert,
 * and the WAITING to ACTIVE switch. If anything fails, the rollback gives the fee back too.
 */
@Service
public class JoinService {

    /** Upper bound on groups re-checked before giving up and opening a new one. */
    private static final int MAX_CANDIDATES = 20;

    private final TournamentRepository tournaments;
    private final TournamentGroupRepository groups;
    private final GroupMemberRepository members;
    private final UserRepository users;
    private final TournamentLifecycleService lifecycle;
    private final LeaderboardService leaderboards;
    private final ApplicationEventPublisher events;
    private final GameProperties props;
    private final TimeSource time;

    public JoinService(TournamentRepository tournaments, TournamentGroupRepository groups,
                       GroupMemberRepository members, UserRepository users, TournamentLifecycleService lifecycle,
                       LeaderboardService leaderboards, ApplicationEventPublisher events, GameProperties props,
                       TimeSource time) {
        this.tournaments = tournaments;
        this.groups = groups;
        this.members = members;
        this.users = users;
        this.lifecycle = lifecycle;
        this.leaderboards = leaderboards;
        this.events = events;
        this.props = props;
        this.time = time;
    }

    @Transactional
    public JoinResponse join(long userId) {
        GameProperties.TournamentRules rules = props.tournament();

        Tournament tournament = openTournament(time.now());

        // Lock the player's row first: two joins by the same player now run one after the other,
        // and the checks below read committed data instead of a stale snapshot.
        User user = users.findByIdForUpdate(userId).orElseThrow(() -> new ApiException(ErrorCode.USER_NOT_FOUND));

        // Check the window again now that we hold our locks: a join that arrived at 19:59:59.9 but
        // waited on the player's row until after 20:00 is too late. (The tournament row is
        // share-locked, so its status can't change under us; only the clock can.)
        Instant now = time.now();
        if (!tournament.isOpenAt(now)) {
            throw new ApiException(ErrorCode.TOURNAMENT_CLOSED);
        }
        if (members.existsByTournamentIdAndUserId(tournament.getId(), userId)) {
            throw new ApiException(ErrorCode.ALREADY_JOINED);
        }
        if (members.hasUnclaimedReward(userId)) {
            throw new ApiException(ErrorCode.UNCLAIMED_REWARD);
        }
        if (user.getLevel() < rules.minLevel()) {
            throw new ApiException(ErrorCode.LEVEL_TOO_LOW);
        }
        long fee = rules.entryFee();
        if (user.getCoins() < fee) {
            throw new ApiException(ErrorCode.NOT_ENOUGH_COINS);
        }

        // The fee goes first, as one conditional UPDATE. The checks above give precise error codes;
        // this statement is what actually guarantees the rule.
        if (users.chargeEntryFee(userId, fee, rules.minLevel()) == 0) {
            throw new ApiException(ErrorCode.NOT_ENOUGH_COINS);
        }

        TournamentGroup group = lockGroupWithRoomFor(tournament.getId(), user.getCountry())
                .orElseGet(() -> groups.saveAndFlush(TournamentGroup.waiting(tournament.getId())));
        // Record the fee actually charged, so a refund returns exactly that.
        members.saveAndFlush(new GroupMember(group.getId(), tournament.getId(), userId, user.getCountry(), fee, now));
        boolean filled = group.addMember(props.groupSize(), now);
        groups.saveAndFlush(group);

        List<GroupMember> roster = members.findRanked(group.getId());
        if (filled) {
            events.publishEvent(new GroupActivated(group.getId(), tournament.getId(),
                    tournament.getStartsAt(), tournament.getEndsAt(),
                    roster.stream().map(m -> new Member(m.getUserId(), m.getCountry(), m.getScoreUpdatedAt())).toList()));
        }
        return new JoinResponse(group.getId(), group.getStatus(), leaderboards.entries(roster));
    }

    private Tournament openTournament(Instant now) {
        Optional<Tournament> open = tournaments.findOpenForShare(now);
        if (open.isEmpty()) {
            // The midnight job may not have run on any instance yet (fresh deploy, restart).
            lifecycle.ensureTournamentFor(now);
            open = tournaments.findOpenForShare(now);
        }
        return open.orElseThrow(() -> new ApiException(ErrorCode.TOURNAMENT_CLOSED));
    }

    /**
     * Finds and locks a WAITING group without a player from this country. SKIP LOCKED makes two
     * same-country joins at the same moment take different groups instead of queueing on one.
     *
     * <p>The candidate query's NOT EXISTS uses a snapshot taken before the lock. Once we hold the
     * lock, every earlier join into that group has committed, so a fresh query sees all its
     * members. If our country turns out to be taken, we skip that group and look again.
     */
    private Optional<TournamentGroup> lockGroupWithRoomFor(long tournamentId, String country) {
        List<Long> skipped = new ArrayList<>(List.of(-1L));
        for (int i = 0; i < MAX_CANDIDATES; i++) {
            Optional<Long> candidate = groups.lockWaitingGroupWithout(tournamentId, country, skipped);
            if (candidate.isEmpty()) {
                return Optional.empty();
            }
            long groupId = candidate.get();
            if (!members.existsByGroupIdAndCountry(groupId, country)) {
                return groups.findById(groupId);
            }
            skipped.add(groupId);
        }
        return Optional.empty();
    }
}
