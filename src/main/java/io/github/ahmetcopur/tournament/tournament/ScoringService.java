package io.github.ahmetcopur.tournament.tournament;

import io.github.ahmetcopur.tournament.config.TimeSource;
import io.github.ahmetcopur.tournament.leaderboard.LeaderboardEvents.ScoreChanged;
import java.time.Instant;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ScoringService {

    private final GroupMemberRepository members;
    private final TournamentWindows windows;
    private final ApplicationEventPublisher events;
    private final TimeSource time;

    public ScoringService(GroupMemberRepository members, TournamentWindows windows, ApplicationEventPublisher events,
                          TimeSource time) {
        this.members = members;
        this.windows = windows;
        this.events = events;
        this.time = time;
    }

    /**
     * +1 score if the user is in an ACTIVE group, inside the caller's level-up transaction (which
     * already holds the player's row). Levels passed while the group is still WAITING don't count.
     * Redis is updated after commit.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordLevelPassed(long userId) {
        members.findScoringMembershipForShare(userId, time.now()).ifPresent(member -> {
            // Read the clock only now that every lock is held: the player row (caller) and the
            // group row (FOR SHARE above). Waiting on either can take us past 20:00, and while we
            // hold the group the 20:00 job can't end it, so this is the moment that decides.
            Instant now = time.now();
            TournamentWindows.Window window = windows.of(member.getTournamentId());
            if (!now.isBefore(window.endsAt())) {
                return;
            }
            members.incrementScore(member.getId(), now);
            // The caller holds the user's row lock, so nobody else changed this score meanwhile.
            events.publishEvent(new ScoreChanged(member.getGroupId(), member.getTournamentId(), userId,
                    member.getCountry(), member.getScore() + 1, now, window.startsAt(), window.endsAt()));
        });
    }
}
