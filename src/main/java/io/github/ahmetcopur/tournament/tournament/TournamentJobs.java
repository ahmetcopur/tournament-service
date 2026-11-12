package io.github.ahmetcopur.tournament.tournament;

import io.github.ahmetcopur.tournament.config.TimeSource;
import io.github.ahmetcopur.tournament.leaderboard.LeaderboardWriter;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Tournament settlement: the two daily jobs from the doc, a reconcile every minute that catches
 * up on anything they missed, and the same work once at startup.
 *
 * <p>They run on their own scheduler thread ({@code settlementScheduler}), so a long leaderboard
 * rebuild or search reconcile on the shared maintenance threads can never delay 20:00.
 */
@Component
@ConditionalOnProperty(name = "game.jobs.enabled", havingValue = "true", matchIfMissing = true)
public class TournamentJobs {

    private static final Logger log = LoggerFactory.getLogger(TournamentJobs.class);

    private final TournamentLifecycleService lifecycle;
    private final TournamentRepository tournaments;
    private final LeaderboardWriter leaderboards;
    private final TimeSource time;

    public TournamentJobs(TournamentLifecycleService lifecycle, TournamentRepository tournaments,
                          LeaderboardWriter leaderboards, TimeSource time) {
        this.lifecycle = lifecycle;
        this.tournaments = tournaments;
        this.leaderboards = leaderboards;
        this.time = time;
    }

    @Scheduled(cron = "${game.tournament.open-cron}", zone = "UTC", scheduler = "settlementScheduler")
    public void openDailyTournament() {
        reconcileTournaments();
    }

    @Scheduled(cron = "${game.tournament.close-cron}", zone = "UTC", scheduler = "settlementScheduler")
    public void closeDailyTournament() {
        reconcileTournaments();
    }

    /**
     * Makes sure today's tournament exists and every tournament past its end is settled (ranks,
     * rewards, refunds). The 00:00 and 20:00 jobs do this on time; running it every minute too
     * means a failure then (Postgres briefly down, say) is repaired a minute later instead of the
     * next day. Both steps are idempotent, so running them often is safe: an ended tournament is
     * never ended twice.
     */
    @Scheduled(initialDelayString = "${game.tournament.reconcile-every:PT1M}",
               fixedDelayString = "${game.tournament.reconcile-every:PT1M}", scheduler = "settlementScheduler")
    public void reconcileTournaments() {
        Instant now = time.now();
        try {
            lifecycle.ensureTournamentFor(now);
        } catch (RuntimeException ex) {
            log.error("Could not make sure today's tournament exists, will retry: {}", ex.getMessage());
        }
        try {
            lifecycle.endOverdue(now);
        } catch (RuntimeException ex) {
            log.error("Could not settle overdue tournaments, will retry: {}", ex.getMessage());
        }
    }

    /**
     * Startup: create today's row if missing (no waiting for midnight while testing), end anything
     * overdue, and rebuild the Redis boards of open tournaments if Redis was wiped.
     */
    @EventListener(ApplicationReadyEvent.class)
    @Order(Ordered.HIGHEST_PRECEDENCE) // before the search reconcile, which waits on Elasticsearch
    public void onStartup() {
        reconcileTournaments();
        tournaments.findByStatus(Tournament.Status.ACTIVE)
                .forEach(t -> leaderboards.rebuildIfMissing(t.getId()));
    }
}
