package io.github.ahmetcopur.tournament.tournament;

import io.github.ahmetcopur.tournament.common.ApiException;
import io.github.ahmetcopur.tournament.common.ErrorCode;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.stereotype.Component;

/**
 * Start and end of each tournament, cached in memory. Every scoring level up needs the start
 * for the tie-break, and the two values never change after the row is created.
 */
@Component
public class TournamentWindows {

    public record Window(Instant startsAt, Instant endsAt) {
    }

    private final TournamentRepository tournaments;
    private final ConcurrentMap<Long, Window> cache = new ConcurrentHashMap<>();

    public TournamentWindows(TournamentRepository tournaments) {
        this.tournaments = tournaments;
    }

    public Window of(long tournamentId) {
        Window cached = cache.get(tournamentId);
        if (cached != null) {
            return cached;
        }
        Tournament t = tournaments.findById(tournamentId)
                .orElseThrow(() -> new ApiException(ErrorCode.TOURNAMENT_NOT_FOUND));
        Window window = new Window(t.getStartsAt(), t.getEndsAt());
        cache.put(tournamentId, window);
        return window;
    }
}
