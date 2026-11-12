package io.github.ahmetcopur.tournament.metrics;

import io.github.ahmetcopur.tournament.leaderboard.LeaderboardEvents.GroupActivated;
import io.github.ahmetcopur.tournament.leaderboard.LeaderboardEvents.ScoreChanged;
import io.github.ahmetcopur.tournament.leaderboard.LeaderboardEvents.TournamentEnded;
import io.github.ahmetcopur.tournament.tournament.TournamentGroupRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Game metrics that HTTP metrics can't show. Request rates, latencies and status codes already
 * come from http_server_requests; this adds what happened inside a request, counted only after
 * the transaction commits, so a rollback is never counted.
 */
@Component
public class GameMetrics {

    private static final Logger log = LoggerFactory.getLogger(GameMetrics.class);

    private final TournamentGroupRepository groups;
    private final Counter pointsScored;
    private final Counter groupsFilled;
    private final Counter refunds;
    private final Map<String, AtomicLong> groupsByStatus = Map.of(
            "WAITING", new AtomicLong(), "ACTIVE", new AtomicLong());
    private final AtomicLong playersWaiting = new AtomicLong();

    public GameMetrics(MeterRegistry meters, TournamentGroupRepository groups) {
        this.groups = groups;
        this.pointsScored = Counter.builder("game.points.scored")
                .description("Level ups that scored a point (player in a full group)")
                .register(meters);
        this.groupsFilled = Counter.builder("game.groups.filled")
                .description("Groups that reached five players and went ACTIVE")
                .register(meters);
        this.refunds = Counter.builder("game.refunds")
                .description("Entries refunded at 20:00 because the group never filled")
                .register(meters);
        groupsByStatus.forEach((status, value) -> Gauge.builder("game.groups", value, AtomicLong::get)
                .description("Groups in open tournaments, by status")
                .tag("status", status)
                .register(meters));
        Gauge.builder("game.players.waiting", playersWaiting, AtomicLong::get)
                .description("Players in groups that are still waiting for more players")
                .register(meters);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onScoreChanged(ScoreChanged event) {
        pointsScored.increment();
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onGroupActivated(GroupActivated event) {
        groupsFilled.increment();
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onTournamentEnded(TournamentEnded event) {
        refunds.increment(event.refundedPlayers());
    }

    /**
     * Refreshes the matchmaking gauges with one small query, every 10 seconds rather than on
     * every scrape, so a slow database can never make /actuator/prometheus slow.
     */
    @Scheduled(fixedDelayString = "PT10S")
    public void refreshGroupGauges() {
        try {
            Map<String, Long> counts = new HashMap<>();
            long waiting = 0;
            for (Object[] row : groups.statsForOpenTournaments()) {
                counts.put((String) row[0], ((Number) row[1]).longValue());
                if ("WAITING".equals(row[0])) {
                    waiting = ((Number) row[2]).longValue();
                }
            }
            groupsByStatus.forEach((status, value) -> value.set(counts.getOrDefault(status, 0L)));
            playersWaiting.set(waiting);
        } catch (RuntimeException ex) {
            log.warn("Could not refresh group gauges: {}", ex.getMessage());
        }
    }
}
