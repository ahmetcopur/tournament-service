package com.example.tournament.leaderboard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

import com.example.tournament.support.IntegrationTestBase;
import com.example.tournament.tournament.Tournament;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * Review finding: finalization records "done" in Postgres and only then deletes the country
 * board's bookkeeping in Redis. If that cleanup fails (Redis error, crash), nothing retries it.
 */
class FinalizationCleanupIntegrationTest extends IntegrationTestBase {

    /** The real template, with one call we can make fail: the multi-key delete used by cleanup. */
    @MockitoSpyBean
    private StringRedisTemplate redisSpy;
    @Autowired
    private LeaderboardWriter writer;

    @AfterEach
    void healRedis() {
        reset(redisSpy);
    }

    private Object boardsFinalizedAt(long tournamentId) {
        return jdbc.queryForObject("SELECT boards_finalized_at FROM tournaments WHERE id = ?", Object.class, tournamentId);
    }

    @Test
    void cleanupThatFailsIsRetriedUntilItIsDone() throws Exception {
        Tournament t = openTournament();
        FullGroup g = fullGroup();
        long tr = g.player("TR");
        levelUp(tr, 2);
        String bookkeeping = layout.bucketKey(t.getId(), layout.shard(tr), layout.bucket(tr));
        assertThat(redis.hasKey(bookkeeping)).isTrue();

        // Redis fails exactly when the final cleanup deletes the bookkeeping.
        doThrow(new RedisSystemException("Redis went away during cleanup", null)).when(redisSpy).delete(anyCollection());
        clock.set(CLOSING_TIME);
        settle();

        assertThat(redis.hasKey(bookkeeping)).as("cleanup failed").isTrue();
        assertThat(boardsFinalizedAt(t.getId())).as("not recorded as finalized while cleanup is unfinished").isNull();

        reset(redisSpy); // Redis is healthy again
        assertThat(writer.reapplyTournaments()).isEqualTo(1);

        assertThat(redis.hasKey(bookkeeping)).as("cleanup finished on retry").isFalse();
        assertThat(boardsFinalizedAt(t.getId())).isNotNull();
        assertThat(countryBoardComplete(t.getId())).isTrue();
        assertThat(redisCountryTotals(t.getId())).containsEntry("TR", 2L);
        assertThat(writer.reapplyTournaments()).as("nothing left to retry").isZero();
    }
}
