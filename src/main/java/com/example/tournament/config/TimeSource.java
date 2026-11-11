package com.example.tournament.config;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.springframework.stereotype.Component;

/**
 * Current time, cut to milliseconds. Postgres would keep microseconds, but the Redis
 * tie-break works in milliseconds, so storing the same value in both keeps their
 * orderings identical.
 */
@Component
public class TimeSource {

    private final Clock clock;

    public TimeSource(Clock clock) {
        this.clock = clock;
    }

    public Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MILLIS);
    }
}
