# Tournament and Leaderboard Service

Spring Boot backend for a match-3 game. Players pass levels to earn coins, join a daily
tournament in groups of five (one player per country), and compete on live group and country
leaderboards.

Java 21 · Spring Boot 3.5 · Spring Data JPA · PostgreSQL 16 · Redis 7 · Flyway · Docker Compose ·
JUnit 5 + Testcontainers · k6 · Actuator + Prometheus

## Run it

Everything in Docker:

```bash
docker compose up --build
```

The tournament window is 00:00 to 20:00 UTC. To try it after 20:00 UTC, stretch the window:

```bash
TOURNAMENT_CLOSES_AT=24h docker compose up --build
```

From an IDE (JDK 21): start only the databases, then run the app.

```bash
docker compose up -d postgres redis
./mvnw spring-boot:run
```

Postgres is published on host port **5433** so it doesn't clash with a local Postgres.

A quick tour:

```bash
curl -s -XPOST localhost:8080/users -H 'Content-Type: application/json' -d '{"username":"alice"}'
curl -s -XPOST localhost:8080/users/1/levels
curl -s localhost:8080/tournaments/current
curl -s -XPOST localhost:8080/tournaments/current/entries -H 'Content-Type: application/json' -d '{"userId":1}'
```

(Joining needs level 20, so level up 19 times first.)

## API

`User` is `{id, username, country, level, coins}`. Errors are `{code, message}`.

| Method | Path | Body | Returns |
|---|---|---|---|
| POST | `/users` | `{username}` | `User` (201) |
| GET | `/users/{id}` | | `User` |
| POST | `/users/{id}/levels` | | `User` after +1 level, +25 coins |
| POST | `/tournaments/current/entries` | `{userId}` | `{groupId, status, leaderboard}` (201) |
| POST | `/users/{id}/rewards/claim` | | `User` with the reward added |
| GET | `/groups/{groupId}/leaderboard` | | `[{userId, username, country, score}]` |
| GET | `/tournaments/{id}/users/{userId}/rank` | | `{rank}` inside the user's group |
| GET | `/tournaments/{id}/countries/leaderboard` | | `[{country, score}]` |
| GET | `/tournaments/current` | | `{id, startsAt, endsAt, status, open}` *(added)* |

| Code | HTTP | When |
|---|---|---|
| `USER_NOT_FOUND` | 404 | Unknown user id |
| `TOURNAMENT_CLOSED` | 409 | Joining outside the window |
| `LEVEL_TOO_LOW` | 409 | Level below 20 |
| `NOT_ENOUGH_COINS` | 409 | Fewer than 1,000 coins |
| `UNCLAIMED_REWARD` | 409 | A reward from an earlier tournament is still unclaimed |
| `ALREADY_JOINED` | 409 | Already in today's tournament |
| `NO_REWARD` | 404 | Nothing to claim |
| `USERNAME_TAKEN` | 409 | *(added)* Username exists |
| `GROUP_NOT_FOUND`, `TOURNAMENT_NOT_FOUND`, `NOT_IN_TOURNAMENT` | 404 | *(added)* Unknown ids on leaderboard reads |
| `VALIDATION_ERROR` | 400 | *(added)* Bad body or path |

Metrics: `GET /actuator/prometheus` (HTTP latency histograms, plus
`leaderboard_redis_write_failures_total`).

## Game rules

All in [application.yml](src/main/resources/application.yml) under `game.*`; no code changes needed.

| Rule | Property | Default |
|---|---|---|
| Starting balance | `starting-coins` | 5,000 coins, level 1 |
| Countries (= group size) | `countries` | TR, US, GB, DE, FR |
| Per level | `coins-per-level` | +25 coins |
| Window | `tournament.opens-at` / `closes-at` | 0h to 20h after 00:00 UTC |
| Entry | `tournament.min-level` / `entry-fee` | level 20, 1,000 coins |
| Rewards by rank | `tournament.rewards` | 10,000, 5,000 |
| Redis key lifetime | `leaderboard.ttl-after-end` | 2 days after the tournament ends |

## How the hard parts work

**Joining** ([JoinService](src/main/java/com/example/tournament/tournament/JoinService.java)).
One Postgres transaction: share-lock the open tournament row, lock the player's row, run the entry
checks, charge the fee with one conditional `UPDATE`, then find a group with
`FOR UPDATE SKIP LOCKED`. Two same-country joins at the same moment skip each other's locked
group rather than queue on it. A failure anywhere rolls the fee back too. The two `UNIQUE`
constraints on `group_members` stay as the safety net.

**Coins.** No read-modify-write. The fee, level ups, refunds and claims are each one `UPDATE`
whose `WHERE` carries the rule. A claim is a single statement: a CTE marks the reward claimed and
the outer `UPDATE` adds the coins, so of two racing claims only one finds a row.

**Level ups during a tournament.** One transaction adds level and coins, then +1 score if the
player's group is `ACTIVE`, the group row share-locked. Redis is updated in an
`@TransactionalEventListener(AFTER_COMMIT)`, so a rolled-back level up never shows on a board.

**End of day** ([TournamentLifecycleService](src/main/java/com/example/tournament/tournament/TournamentLifecycleService.java)).
One transaction: mark the tournament `ENDED` (waits for in-flight joins), end every group (waits
for in-flight level ups), rank full groups by score then by who reached it first, pay ranks 1 and 2,
and refund the fee to players whose group never filled. Ranks come from Postgres, so a Redis
problem can't hand out wrong rewards. Running it twice, or on two instances, does nothing the
second time. On startup the app creates today's row if it's missing and ends any tournament
whose window has already passed.

**Redis and Postgres drifting apart.** Postgres is the source of truth. Reads fall back to
Postgres when Redis is down or a board is missing or incomplete, and a group board read from
Postgres is written back to Redis. A missing country board is rebuilt from Postgres sums. At
20:00 all boards are rewritten from the final Postgres values. At startup, missing boards for
open tournaments are rebuilt.

**Ties.** A Redis sorted set holds one double per member, so score and time are packed into one
value: `score × 10^9 + (10^9 − 1 − ms since tournament start)`. A higher score always wins, and
with equal scores the earlier time wins, matching the 20:00 ranking in Postgres
([LeaderboardScore](src/main/java/com/example/tournament/leaderboard/LeaderboardScore.java)).

## Where this differs from the design doc, and why

1. **Re-check after the lock in matchmaking.** The candidate query's `NOT EXISTS` runs on a
   snapshot taken *before* the row lock. If another join committed a player of my country into
   that group in between, Postgres re-checks the group row's own columns but not the
   `group_members` subquery. Once the lock is held, every earlier joiner of that group has
   committed, so a fresh query sees them all; if my country is taken, that group is skipped.
   Without this the `UNIQUE (group_id, country)` net would turn the race into a failed join.
2. **Joins lock the player's row** (`FOR UPDATE`). Two joins from the same player then run one
   after the other, and the checks return the right error code (`ALREADY_JOINED` rather than a
   constraint error). The doc's conditional `UPDATE` is still what enforces the fee rule.
3. **Joins share-lock the tournament row, and the 20:00 job updates it first.** Otherwise a join
   that opens a new group at 19:59:59.999 could commit after the job listed the groups, leaving a
   `WAITING` group whose fee is never refunded.
4. **Level ups share-lock the group and check `ends_at`**, so no points land after 20:00 or after
   the ranking has been read.
5. **Tie-break in milliseconds, with `10^9 − 1 − ms`.** Seconds tie often, and Postgres stores
   finer times, so the two orderings could disagree. With `10^9 − s`, a score reached at second 0
   encodes as exactly `(score + 1) × 10^9` and decodes to the wrong score. Times are cut to
   milliseconds before they're stored in either place.
6. **Zero scores sort by join time.** Members enter Redis with the encoded join time instead of a
   plain 0 (and `score_updated_at` starts at join time). Otherwise Redis would break zero-score
   ties by user id and Postgres by time.
7. **`ZADD GT` instead of plain `ZADD`.** After-commit listeners for two level ups run on
   different request threads and can reach Redis out of order; `GT` never moves a player back.
8. **Country board rebuilt, not incremented from zero.** If `lb:country:{id}` is missing (wiped or
   expired), the Lua script skips `ZINCRBY` and the app rebuilds the board from Postgres sums.
   Otherwise a wiped board would restart from 0.
9. **After-commit reads reuse the request's connection.** At first the rebuild read used
   `REQUIRES_NEW`. During an after-commit callback Spring still holds the request's connection, so
   each request needed a second one. In the 200-thread test, all 20 pooled connections were held
   by requests waiting for another, and joins timed out after 30 s. Propagation `REQUIRED` fixed it
   ([LeaderboardWriter](src/main/java/com/example/tournament/leaderboard/LeaderboardWriter.java)).
10. **Small additions:** `GET /tournaments/current`, the extra error codes above, named
    constraints (mapped to error codes), and indexes for the level-up lookup, the unclaimed-reward
    check and the end-of-day queries.

## Tests

```bash
./mvnw test        # needs Docker for Testcontainers
```

35 tests: unit tests for the tie-break encoding and level up, plus integration tests against real
Postgres and Redis containers:

- **Users:** create, duplicate name, validation, level up.
- **Joining:** each entry rule and error code, group fill to `ACTIVE`, same country going to
  another group, score counting only once the group is full.
- **Leaderboards:** order with tie-break, rank, country sums, Redis wipe recovery, key expiry.
- **End of day:** ranks, rewards, refunds, idempotency, no points after 20:00, claims (including
  10 concurrent claims paying once), unclaimed reward blocking the next day.
- **Concurrency:** 200 players joining at once. No group has two players from one country,
  everyone was charged exactly once, group counts match members, and every `ACTIVE` group is in
  Redis. Also one player sending 20 joins at once: charged once.

## Load test

[load-test/k6.js](load-test/k6.js) creates 250 players, levels them to 20, joins them all, then
sends a constant request rate: 60% level ups, 30% group boards, 5% rank lookups, 5% country board.

```bash
k6 run -e BASE_URL=http://localhost:8080 load-test/k6.js
# or, against the compose stack:
docker run --rm -i --network tournament-service_default -v "$PWD/load-test":/load-test -w / \
  grafana/k6:latest run -e BASE_URL=http://app:8080 /load-test/k6.js
```

Results from 2026-11-11, on an Apple Silicon laptop. Docker Desktop had 8 CPUs and 4 GB, and the
app, Postgres, Redis and k6 all shared that VM. Hikari pool 20, default Tomcat settings.

**400 requests/s for 60 s** (29,252 requests, 0 failed):

| Endpoint | p50 | p99 |
|---|---|---|
| Level up (Postgres transaction + Redis script) | 1.2 ms | 3.7 ms |
| Group leaderboard | 0.9 ms | 2.9 ms |
| Rank | 1.8 ms | 4.7 ms |
| Country leaderboard | 0.3 ms | 1.2 ms |

**1,500 requests/s for 30 s** (50,049 requests, 0 failed):

| Endpoint | p50 | p99 |
|---|---|---|
| Level up | 1.1 ms | 9.4 ms |
| Group leaderboard | 0.6 ms | 6.3 ms |
| Rank | 1.1 ms | 8.7 ms |
| Country leaderboard | 0.3 ms | 3.3 ms |

After the 400/s run, Redis matched Postgres exactly: all 205 scored members and all five country
totals, with `leaderboard_redis_write_failures_total` at 0.

## Not done / known limits

- **Elasticsearch player search** (an optional extra in the plan) is not built.
- **Redis Cluster:** the level-up script touches a group key and a country key, which can sit in
  different hash slots. For a cluster, give both keys a common hash tag or split the script.
- **Country board during a wipe recovery** can be off by a few points while increments are in
  flight. Group boards heal exactly, and the 20:00 rewrite makes country totals exact.
- **Refunds use the current `entry-fee`.** Changing the fee mid-day refunds the new amount.
  Storing the paid fee per member would fix it.
