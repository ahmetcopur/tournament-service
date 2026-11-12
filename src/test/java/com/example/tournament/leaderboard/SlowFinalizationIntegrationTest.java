package com.example.tournament.leaderboard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;

import com.example.tournament.support.IntegrationTestBase;
import com.example.tournament.tournament.Tournament;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * Review finding: after a tournament's Postgres settlement commits, the final Redis rebuild ran on
 * the settlement thread itself. With a large tournament or a slow Redis it held that thread, so
 * the next overdue tournament waited for Redis. Settlement must not wait for leaderboards.
 */
class SlowFinalizationIntegrationTest extends IntegrationTestBase {

    private static final long SLOW_SCRIPT_MILLIS = 300;

    @MockitoSpyBean
    private StringRedisTemplate redisSpy;

    private final ExecutorService settlementThread = Executors.newSingleThreadExecutor();

    @AfterEach
    void healRedis() throws Exception {
        reset(redisSpy);
        settlementThread.shutdownNow();
        boardWriter.awaitFinalization(Duration.ofSeconds(60)); // leave nothing running for other tests
    }

    private String status(long tournamentId) {
        return jdbc.queryForObject("SELECT status FROM tournaments WHERE id = ?", String.class, tournamentId);
    }

    @Test
    void aSlowFinalRebuildDoesNotHoldUpTheNextSettlement() throws Exception {
        // Yesterday's tournament was never settled (say every instance was down at 20:00).
        clock.set(MORNING.minus(Duration.ofDays(1)));
        Tournament yesterday = openTournament();
        fullGroup();
        // Today's is over too.
        clock.set(MORNING);
        Tournament today = openTournament();
        fullGroup();

        // From now on every Redis script call takes 300 ms: a slow Redis, or a huge tournament.
        // A final rebuild makes more than a dozen of them.
        doAnswer(call -> {
            Thread.sleep(SLOW_SCRIPT_MILLIS);
            return call.callRealMethod();
        }).when(redisSpy).execute(any(RedisScript.class), anyList(), any(Object[].class));

        clock.set(CLOSING_TIME);
        long started = System.nanoTime();
        Future<Integer> settling = settlementThread.submit(() -> lifecycle.endOverdue(clock.instant()));

        // Both are settled in Postgres promptly; the Redis work must not be in the way.
        for (int i = 0; i < 40 && !"ENDED".equals(status(today.getId())); i++) {
            Thread.sleep(50);
        }
        long millis = (System.nanoTime() - started) / 1_000_000;
        assertThat(status(yesterday.getId())).isEqualTo("ENDED");
        assertThat(status(today.getId())).as("second settlement, %d ms after the first started", millis).isEqualTo("ENDED");
        assertThat(millis).as("both settled within").isLessThan(2_000);
        assertThat(settling.get(60, TimeUnit.SECONDS)).isEqualTo(2);

        // The slow Redis work still completes, in the background, for both.
        boardWriter.awaitFinalization(Duration.ofSeconds(60));
        for (Tournament t : java.util.List.of(yesterday, today)) {
            assertThat(jdbc.queryForObject("SELECT boards_finalized_at FROM tournaments WHERE id = ?", Object.class, t.getId()))
                    .as("tournament %d finalized", t.getId()).isNotNull();
        }
    }
}
