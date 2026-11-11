package com.example.tournament.tournament;

import com.example.tournament.config.TimeSource;
import com.example.tournament.leaderboard.LeaderboardWriter;
import java.time.Instant;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** The two daily jobs from the doc, plus the same work once at startup. */
@Component
@ConditionalOnProperty(name = "game.jobs.enabled", havingValue = "true", matchIfMissing = true)
public class TournamentJobs {

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

    @Scheduled(cron = "${game.tournament.open-cron}", zone = "UTC")
    public void openDailyTournament() {
        lifecycle.ensureTournamentFor(time.now());
    }

    /** Also ends any tournament an earlier run missed (instance down at 20:00). */
    @Scheduled(cron = "${game.tournament.close-cron}", zone = "UTC")
    public void closeDailyTournament() {
        lifecycle.endOverdue(time.now());
    }

    /**
     * Startup: create today's row if missing (no waiting for midnight while testing), end anything
     * overdue, and rebuild the Redis boards of open tournaments if Redis was wiped.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        Instant now = time.now();
        lifecycle.ensureTournamentFor(now);
        lifecycle.endOverdue(now);
        tournaments.findByStatus(Tournament.Status.ACTIVE)
                .forEach(t -> leaderboards.rebuildIfMissing(t.getId()));
    }
}
