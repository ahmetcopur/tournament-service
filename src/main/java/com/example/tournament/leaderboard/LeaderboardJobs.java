package com.example.tournament.leaderboard;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Brings Redis back in line with Postgres after writes that never arrived. Every Redis write is
 * idempotent, so re-applying from Postgres is always safe.
 */
@Component
@ConditionalOnProperty(name = "game.jobs.enabled", havingValue = "true", matchIfMissing = true)
public class LeaderboardJobs {

    private static final Logger log = LoggerFactory.getLogger(LeaderboardJobs.class);

    private final LeaderboardWriter writer;

    public LeaderboardJobs(LeaderboardWriter writer) {
        this.writer = writer;
    }

    /** Groups whose write failed (Redis timed out, was restarting, ...): repaired within seconds. */
    @Scheduled(fixedDelayString = "${game.leaderboard.retry-every:PT5S}")
    public void retryMissedWrites() {
        try {
            writer.retryMissedWrites();
        } catch (RuntimeException ex) {
            log.debug("Leaderboard retry failed, will try again: {}", ex.getMessage());
        }
    }

    /** Writes lost without an error anyone saw (an instance died after commit): repaired within minutes. */
    @Scheduled(initialDelayString = "${game.leaderboard.reapply-every:PT5M}",
               fixedDelayString = "${game.leaderboard.reapply-every:PT5M}")
    public void reapplyTournaments() {
        try {
            writer.reapplyTournaments();
        } catch (RuntimeException ex) {
            log.warn("Leaderboard re-apply failed, will try again: {}", ex.getMessage());
        }
    }
}
