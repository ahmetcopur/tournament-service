package com.example.tournament.tournament;

import com.example.tournament.config.GameProperties;
import com.example.tournament.leaderboard.LeaderboardEvents.TournamentEnded;
import com.example.tournament.user.UserRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Creates each day's tournament and ends it: final ranks, rewards and refunds. */
@Service
public class TournamentLifecycleService {

    private static final Logger log = LoggerFactory.getLogger(TournamentLifecycleService.class);

    private final TournamentRepository tournaments;
    private final TournamentGroupRepository groups;
    private final GroupMemberRepository members;
    private final UserRepository users;
    private final ApplicationEventPublisher events;
    private final GameProperties props;
    private final TransactionTemplate tx;

    public TournamentLifecycleService(TournamentRepository tournaments, TournamentGroupRepository groups,
                                      GroupMemberRepository members, UserRepository users,
                                      ApplicationEventPublisher events, GameProperties props,
                                      PlatformTransactionManager txManager) {
        this.tournaments = tournaments;
        this.groups = groups;
        this.members = members;
        this.users = users;
        this.events = events;
        this.props = props;
        this.tx = new TransactionTemplate(txManager);
    }

    /** The tournament for the UTC day that contains {@code now}, created if it doesn't exist yet. */
    public Tournament ensureTournamentFor(Instant now) {
        Instant startsAt = startOfDay(now).plus(props.tournament().opensAt());
        Instant endsAt = startOfDay(now).plus(props.tournament().closesAt());
        return tx.execute(status -> {
            if (tournaments.insertIfAbsent(startsAt, endsAt) == 1) {
                log.info("Created tournament for {} to {}", startsAt, endsAt);
            }
            return tournaments.findByStartsAt(startsAt).orElseThrow();
        });
    }

    /** Today's tournament, whether or not it is open right now. */
    public Optional<Tournament> todays(Instant now) {
        return tournaments.findByStartsAt(startOfDay(now).plus(props.tournament().opensAt()));
    }

    /** Ends every ACTIVE tournament whose window is over. Safe to run on several instances at once. */
    public int endOverdue(Instant now) {
        List<Tournament> overdue = tournaments.findByStatusAndEndsAtLessThanEqualOrderByEndsAtAsc(Tournament.Status.ACTIVE, now);
        int ended = 0;
        for (Tournament t : overdue) {
            try {
                if (Boolean.TRUE.equals(tx.execute(status -> end(t.getId())))) {
                    ended++;
                }
            } catch (RuntimeException ex) {
                // The transaction rolled back, so nothing was half done; the tournament is still
                // ACTIVE and the next reconcile (every minute) tries again. One failing tournament
                // doesn't stop the others.
                log.error("Could not end tournament {}, will retry: {}", t.getId(), ex.getMessage());
            }
        }
        return ended;
    }

    /**
     * One transaction. Each step waits for the locks held by in-flight joins and level ups,
     * and each later statement takes a fresh snapshot, so it sees everything they committed.
     */
    private boolean end(long tournamentId) {
        // 1. Close entry. Waits for joins holding FOR SHARE on this row; later joins find nothing open.
        if (tournaments.markEnded(tournamentId) == 0) {
            return false; // another instance got here first
        }
        // 2. Stop scoring. Waits for level ups holding FOR SHARE on their group.
        groups.endAll(tournamentId);
        // 3. Rank full groups by score, then by who got there first, and pay out.
        members.assignFinalRanks(tournamentId);
        List<Long> rewards = props.tournament().rewards();
        for (int rank = 1; rank <= rewards.size(); rank++) {
            members.assignReward(tournamentId, rank, rewards.get(rank - 1));
        }
        // 4. Groups that never filled up get back what each player paid, and no reward.
        int refunded = users.refundUnstartedEntries(tournamentId);
        events.publishEvent(new TournamentEnded(tournamentId, refunded));
        log.info("Ended tournament {}; refunded {} players in unfilled groups", tournamentId, refunded);
        return true;
    }

    private static Instant startOfDay(Instant now) {
        return LocalDate.ofInstant(now, ZoneOffset.UTC).atStartOfDay(ZoneOffset.UTC).toInstant();
    }
}
