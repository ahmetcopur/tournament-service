package com.example.tournament.support;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.example.tournament.TournamentApplication;
import com.example.tournament.leaderboard.CountryBoardLayout;
import com.example.tournament.leaderboard.LeaderboardWriter;
import com.example.tournament.security.TokenService;
import com.example.tournament.tournament.Tournament;
import com.example.tournament.tournament.TournamentLifecycleService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Connection;
import java.sql.Statement;
import javax.sql.DataSource;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations.TypedTuple;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.elasticsearch.ElasticsearchContainer;

/**
 * Real Postgres and Redis in containers, started once and shared by every test class.
 * Scheduled jobs are off; tests move the clock and call the lifecycle service themselves.
 */
@SpringBootTest(classes = {TournamentApplication.class, TestClockConfig.class})
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "game.jobs.enabled=false",
        "auth.token-secret=integration-tests-only-secret-0123456789abcdef",
        "management.server.port=-1",
        "game.leaderboard.country-board-cache=0s"})
public abstract class IntegrationTestBase {

    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    /**
     * Redis in cluster mode, as a single node that owns all 16,384 slots. The app talks to it as a
     * plain server, but the node still rejects any command whose keys sit in different slots
     * (CROSSSLOT), so every test also proves the app is safe to run on a Redis Cluster.
     */
    @ServiceConnection(name = "redis")
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine")
            .withExposedPorts(6379)
            .withCommand("redis-server", "--cluster-enabled", "yes", "--save", "", "--appendonly", "no");

    @ServiceConnection
    static final ElasticsearchContainer ELASTICSEARCH =
            new ElasticsearchContainer("docker.elastic.co/elasticsearch/elasticsearch:8.18.8")
                    .withEnv("xpack.security.enabled", "false")
                    .withEnv("ES_JAVA_OPTS", "-Xms512m -Xmx512m");

    static {
        POSTGRES.start();
        REDIS.start();
        assignAllSlots(REDIS);
        ELASTICSEARCH.start();
    }

    private static void assignAllSlots(GenericContainer<?> redis) {
        try {
            redis.execInContainer("redis-cli", "cluster", "addslotsrange", "0", "16383");
            for (int i = 0; i < 100; i++) {
                if (redis.execInContainer("redis-cli", "cluster", "info").getStdout().contains("cluster_state:ok")) {
                    return;
                }
                Thread.sleep(100);
            }
            throw new IllegalStateException("Redis cluster did not become ready");
        } catch (Exception e) {
            throw new IllegalStateException("Could not set up the Redis cluster node", e);
        }
    }

    /**
     * 10:00 UTC today, inside the 00:00 to 20:00 window. Today and not a fixed date, because Redis
     * expiry runs on the real clock: boards of a long-past tournament would expire immediately.
     */
    protected static final Instant MORNING = TestClockConfig.TODAY.atTime(10, 0).toInstant(ZoneOffset.UTC);
    protected static final Instant CLOSING_TIME = TestClockConfig.TODAY.atTime(20, 0).toInstant(ZoneOffset.UTC);
    protected static final List<String> COUNTRIES = List.of("TR", "US", "GB", "DE", "FR");

    private static final AtomicInteger NAMES = new AtomicInteger();

    @Autowired
    protected MockMvc mvc;
    @Autowired
    protected ObjectMapper json;
    @Autowired
    protected JdbcTemplate jdbc;
    @Autowired
    protected StringRedisTemplate redis;
    @Autowired
    protected MutableClock clock;
    @Autowired
    protected TournamentLifecycleService lifecycle;
    @Autowired
    protected DataSource dataSource;
    @Autowired
    protected TokenService tokens;
    @Autowired
    protected CountryBoardLayout layout;
    @Autowired
    protected LeaderboardWriter boardWriter;

    @BeforeEach
    void resetState() throws Exception {
        boardWriter.awaitFinalization(Duration.ofSeconds(60)); // no background rebuild from the last test
        jdbc.execute("TRUNCATE group_members, tournament_groups, tournaments, users");
        flushRedis();
        clock.set(MORNING);
    }

    protected void flushRedis() {
        redis.execute((RedisCallback<Object>) c -> {
            c.serverCommands().flushAll();
            return null;
        });
    }

    /** Ends every overdue tournament, then waits for the final board rebuilds (they run in the background). */
    protected int settle() throws Exception {
        int ended = lifecycle.endOverdue(clock.instant());
        boardWriter.awaitFinalization(Duration.ofSeconds(60));
        return ended;
    }

    protected Tournament openTournament() {
        return lifecycle.ensureTournamentFor(clock.instant());
    }

    /** A player who already meets the entry rules: level 20, 5,000 coins. */
    protected long newPlayer(String country) {
        return newPlayer(country, 20, 5000);
    }

    protected long newPlayer(String country, int level, long coins) {
        return jdbc.queryForObject(
                "INSERT INTO users (username, country, level, coins) VALUES (?, ?, ?, ?) RETURNING id",
                Long.class, "player" + NAMES.incrementAndGet(), country, level, coins);
    }

    /** One player per country, joined in the order TR, US, GB, DE, FR, one second apart. */
    protected FullGroup fullGroup() throws Exception {
        List<Long> ids = new ArrayList<>();
        long groupId = -1;
        for (String country : COUNTRIES) {
            long id = newPlayer(country);
            ids.add(id);
            groupId = body(join(id)).get("groupId").asLong();
            clock.advance(Duration.ofSeconds(1));
        }
        return new FullGroup(groupId, ids);
    }

    public record FullGroup(long groupId, List<Long> playerIds) {
        public long player(String country) {
            return playerIds.get(COUNTRIES.indexOf(country));
        }
    }

    protected long coins(long userId) {
        return jdbc.queryForObject("SELECT coins FROM users WHERE id = ?", Long.class, userId);
    }

    protected int score(long userId) {
        return jdbc.queryForObject("SELECT score FROM group_members WHERE user_id = ? ORDER BY id DESC LIMIT 1",
                Integer.class, userId);
    }

    protected ResultActions join(long userId) throws Exception {
        return mvc.perform(post("/tournaments/current/entries")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"userId\":" + userId + "}")
                .with(as(userId)));
    }

    protected ResultActions levelUp(long userId) throws Exception {
        return mvc.perform(post("/users/{id}/levels", userId).with(as(userId)));
    }

    protected ResultActions levelUp(long userId, int times) throws Exception {
        ResultActions last = null;
        for (int i = 0; i < times; i++) {
            clock.advance(Duration.ofMillis(10));
            last = levelUp(userId);
        }
        return last;
    }

    protected ResultActions claim(long userId) throws Exception {
        return mvc.perform(post("/users/{id}/rewards/claim", userId).with(as(userId)));
    }

    protected ResultActions groupBoard(long groupId) throws Exception {
        return mvc.perform(get("/groups/{id}/leaderboard", groupId).with(anyPlayer()));
    }

    /** The country board as Redis holds it: every slice added up, complete or not. */
    protected Map<String, Long> redisCountryTotals(long tournamentId) {
        Map<String, Long> totals = new TreeMap<>();
        for (int shard = 0; shard < layout.shards(); shard++) {
            Set<TypedTuple<String>> slice = redis.opsForZSet().rangeWithScores(layout.totals(tournamentId, shard), 0, -1);
            if (slice != null) {
                slice.forEach(t -> totals.merge(t.getValue(), t.getScore().longValue(), Long::sum));
            }
        }
        return totals;
    }

    protected boolean countryBoardComplete(long tournamentId) {
        for (int shard = 0; shard < layout.shards(); shard++) {
            if (!"1".equals(redis.opsForHash().get(layout.meta(tournamentId, shard), "ready"))) {
                return false;
            }
        }
        return true;
    }

    /** Sends the request with a valid token for {@code userId}, i.e. as that player. */
    protected RequestPostProcessor as(long userId) {
        String token = tokens.issue(userId).token();
        return request -> {
            request.addHeader("Authorization", "Bearer " + token);
            return request;
        };
    }

    /** Any signed-in player, for endpoints that don't act for a particular player (boards, search). */
    protected RequestPostProcessor anyPlayer() {
        return as(0);
    }

    protected JsonNode body(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString());
    }

    /** Freezes Redis (docker pause): commands time out until {@link #unpauseRedis()}. */
    protected static void pauseRedis() {
        REDIS.getDockerClient().pauseContainerCmd(REDIS.getContainerId()).exec();
    }

    protected static void unpauseRedis() {
        REDIS.getDockerClient().unpauseContainerCmd(REDIS.getContainerId()).exec();
    }

    /** Freezes Elasticsearch (docker pause): requests hang until {@link #unpauseElasticsearch()}. */
    protected static void pauseElasticsearch() {
        ELASTICSEARCH.getDockerClient().pauseContainerCmd(ELASTICSEARCH.getContainerId()).exec();
    }

    protected static void unpauseElasticsearch() {
        ELASTICSEARCH.getDockerClient().unpauseContainerCmd(ELASTICSEARCH.getContainerId()).exec();
    }

    /**
     * Holds a row lock in a separate transaction until the returned handle is closed, so a
     * request touching that row has to wait. Use the lock a real competitor would take: for a
     * users row that's FOR NO KEY UPDATE (what an UPDATE of level/coins takes). FOR UPDATE would
     * also block foreign-key checks, which no real request does.
     */
    protected AutoCloseable holdLock(String lockSql) throws Exception {
        Connection connection = dataSource.getConnection();
        connection.setAutoCommit(false);
        try (Statement statement = connection.createStatement()) {
            statement.execute(lockSql);
        }
        return () -> {
            connection.rollback();
            connection.close();
        };
    }

    /** Waits until some statement starting with {@code sqlPrefix} is blocked on a lock. */
    protected void awaitBlockedOn(String sqlPrefix) throws InterruptedException {
        for (int i = 0; i < 200; i++) {
            Integer waiting = jdbc.queryForObject(
                    "SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock' AND query LIKE ?",
                    Integer.class, sqlPrefix + "%");
            if (waiting != null && waiting > 0) {
                return;
            }
            Thread.sleep(25);
        }
        throw new AssertionError("No statement starting with '" + sqlPrefix + "' ever waited on a lock");
    }
}
