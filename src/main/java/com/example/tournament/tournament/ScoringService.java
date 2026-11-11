package com.example.tournament.tournament;

import com.example.tournament.leaderboard.LeaderboardEvents.ScoreChanged;
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

    public ScoringService(GroupMemberRepository members, TournamentWindows windows, ApplicationEventPublisher events) {
        this.members = members;
        this.windows = windows;
        this.events = events;
    }

    /**
     * +1 score if the user is in an ACTIVE group, inside the caller's level-up transaction.
     * Levels passed while the group is still WAITING don't count. Redis is updated after commit.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordLevelPassed(long userId, Instant now) {
        members.findScoringMembershipForShare(userId, now).ifPresent(member -> {
            members.incrementScore(member.getId(), now);
            TournamentWindows.Window window = windows.of(member.getTournamentId());
            // The caller holds the user's row lock, so nobody else changed this score meanwhile.
            events.publishEvent(new ScoreChanged(member.getGroupId(), member.getTournamentId(), userId,
                    member.getCountry(), member.getScore() + 1, now, window.startsAt(), window.endsAt()));
        });
    }
}
