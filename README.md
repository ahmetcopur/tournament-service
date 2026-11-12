# Tournament and Leaderboard Service

Spring Boot backend for a match-3 game. Players pass levels to earn coins, join a daily
tournament in groups of five (one player per country), and compete on live group and country
leaderboards.

Java 21 · Spring Boot 3.5 · Spring Data JPA · PostgreSQL 16 · Redis 7 · Elasticsearch 8 · Flyway ·
Docker Compose · JUnit 5 + Testcontainers · k6 · Actuator, Prometheus and Grafana

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
docker compose up -d postgres redis elasticsearch
./mvnw spring-boot:run
```

Postgres is published on host port **5433** so it doesn't clash with a local Postgres.
Elasticsearch runs with security off, which is fine on a laptop and nowhere else.

With the full stack up, the Grafana dashboard is at <http://localhost:3000> (it's the home
page; anyone can view, `admin`/`admin` can edit) and Prometheus at <http://localhost:9090>.

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
| GET | `/tournaments/current` | | `{id, startsAt, endsAt, status, open}`; creates today's row if needed *(added)* |
| GET | `/users/search?q=ahm&country=TR&limit=20` | | `[User]`, best match first *(added)* |

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
| `VALIDATION_ERROR` | 400 | *(added)* Bad body, path or query |
| `SEARCH_UNAVAILABLE` | 503 | *(added)* Elasticsearch can't be reached; everything else still works |

Metrics are at `GET /actuator/prometheus`; see [Monitoring](#monitoring).

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
| Search index check | `search.reconcile-every` | every 10 minutes |

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

**The 20:00 cutoff.** A request counts only if it is *processed* before 20:00, meaning it holds
**all** its locks before then, not merely arrived before then. Scoring reads the clock after it holds
both the player's row and the group's row. The time it reads is both the cutoff check and the
tie-break time, and while it holds the group, the 20:00 job can't end it. Joins check the window
again once they hold their locks. A request that arrived at 19:59:59.9 but waited on any lock past
20:00 scores nothing and can't join. Once the 20:00 job has ended a group, no point can land in it
either, because scoring requires an `ACTIVE` group.

**End of day** ([TournamentLifecycleService](src/main/java/com/example/tournament/tournament/TournamentLifecycleService.java)).
One transaction: mark the tournament `ENDED` (waits for in-flight joins), end every group (waits
for in-flight level ups), rank full groups by score then by who reached it first, pay ranks 1 and 2,
and refund players whose group never filled exactly the fee each one paid (stored on the entry,
so a fee change mid-day doesn't matter). Ranks come from Postgres, so a Redis
problem can't hand out wrong rewards. Running it twice, or on two instances, does nothing the
second time. On startup the app creates today's row if it's missing and ends any tournament
whose window has already passed.

**Redis and Postgres drifting apart.** Postgres is the source of truth, and every Redis write is
idempotent, so a write can be retried, arrive late, or race a rebuild without counting a point twice
or losing one.

- *Group boards* keep each player's highest value (`ZADD GT`). If a board is missing or
  incomplete, the read is answered from Postgres and the board is written back.
- *Country boards* ([country_apply.lua](src/main/resources/redis/country_apply.lua)) store, next to
  the totals, the highest score already counted for each player. An update says "player 42 is at
  score 7"; only the part not yet counted is added. A rebuild replays every player's Postgres score
  through the same script and then marks the board complete. Every update committed before the
  rebuild's Postgres read is in the replay, and every later one applies itself, so the board ends up
  exact. Rebuilds run one at a time per tournament (a Redis lock), and until a board is complete,
  reads come from Postgres.
- *Missed writes.* Idempotency makes retries safe, but something has to retry. When a Redis write
  fails after the commit, its group is remembered and re-applied from Postgres every 5 seconds
  until it succeeds. Writes lost without anyone seeing an error, for example an instance dying
  right after its commit, are covered by a full re-apply of every open tournament every 5 minutes
  (one instance does it, via a lock that expires). A timeout doesn't even mean the write was lost:
  it can land late, and the retry applying it again changes nothing.
- At 20:00 and at startup, boards are rebuilt from Postgres. A successful final rebuild is recorded
  in `tournaments.boards_finalized_at`. If it fails (Redis down at 20:00, or the instance dies), the
  tournament stays unfinalized and the 5-minute job retries it until it succeeds, for as long as its
  boards would still exist. Only those tournaments are retried, never the whole history.

**Redis Cluster.** Every command touches one key, or keys sharing a hash tag: a tournament's
country board `lb:country:{7}` and its bookkeeping `lb:country:{7}:applied` sit in one slot, so
the script can update both atomically. Group boards are independent keys and spread across nodes.
The tests run Redis in cluster mode, which rejects any cross-slot command, so the suite checks this
on every run. Switching is configuration only: set `spring.data.redis.cluster.nodes`.

**Player search** ([PlayerSearchService](src/main/java/com/example/tournament/search/PlayerSearchService.java)).
Elasticsearch holds only what people search by (id, username, country), written once at sign up
after the commit. Results rank the exact name first, then names starting with the query
(edge n-grams), then names within a typo or two (fuzzy). Level and coins come fresh from Postgres
for the ids found, so level ups never touch Elasticsearch, and a hit is returned only if Postgres
agrees on who the player is. A failed index write never fails a sign up. At startup and every
10 minutes, a reconcile job makes the index match Postgres player by player, not by document count:
it (re)indexes missing or wrong documents and deletes documents whose player doesn't exist. If
Elasticsearch is down, only search returns 503.

**Ties.** A Redis sorted set holds one double per member, so score and time are packed into one
value: `score × 10^9 + (10^9 − 1 − ms since tournament start)`. A higher score always wins, and
with equal scores the earlier time wins
([LeaderboardScore](src/main/java/com/example/tournament/leaderboard/LeaderboardScore.java)). Two
players can still reach the same score in the same millisecond. Redis would order them by member
string, so `"15"` before `"14"`. The ranking breaks such ties by the lower user id, and because a
group board has only five entries, the read path re-sorts them the same way. The live board, the
rank endpoint and the 20:00 ranking always agree.

## Monitoring

Docker Compose runs Prometheus (scraping the app every 5 s) and Grafana with a provisioned
dashboard, [tournament-service.json](monitoring/grafana/dashboards/tournament-service.json).

![Grafana dashboard under load](docs/grafana-dashboard.jpg)

- **Traffic:** requests per second, p50 and p99 latency per endpoint, and errors per second by
  API error code. HTTP metrics only show "409"; `api_errors_total{code}` shows which rule said no.
- **Game:** level-ups against points actually scored (only players in full groups score), joins
  against groups filled, and a matchmaking gauge: waiting groups and the players stuck in them. A
  growing waiting count means one country is short of players. Also refunds and reward claims.
- **Dependencies:** the Postgres connection pool (active, idle, **pending**), Redis command
  latency, Redis write failures, search index failures, country board rebuilds, and JVM heap.

Metrics the app adds on top of Spring Boot's HTTP, JVM, pool and Redis client metrics:

| Metric | Meaning |
|---|---|
| `api_errors_total{code}` | Error responses by code; every code starts at 0 so the first one shows in `rate()` |
| `game_points_scored_total` | Level-ups that scored a point, counted after commit |
| `game_groups_filled_total` | Groups that reached five players |
| `game_refunds_total` | Entries refunded at 20:00 |
| `game_groups{status}`, `game_players_waiting` | Groups and waiting players in open tournaments, refreshed every 10 s (never queried per scrape) |
| `leaderboard_redis_write_failures_total` | Redis writes that failed after the Postgres commit |
| `leaderboard_country_rebuilds_total` | Country boards rebuilt from Postgres: one per tournament start and per 20:00 is normal |
| `leaderboard_redis_write_repairs_total` | Groups whose failed Redis write was re-applied from Postgres |
| `search_index_failures_total` | Players not written to Elasticsearch (the reconcile job retries) |
| `search_reconcile_fixes_total` | Documents the reconcile job (re)indexed or deleted |
| `search_stale_hits_total` | Search hits dropped because Postgres disagreed on who the player is |

Building the dashboard turned up three things:
- **Startup race:** a fresh instance could report healthy while today's tournament row didn't
  exist yet, so `/tournaments/current` briefly returned 404. That endpoint now creates the row,
  and tournament startup runs before the search reconcile.
- **A wasted rebuild:** startup rebuilt an empty country board that the first full group then
  threw away and rebuilt.
- **What a stall looks like:** in the screenshot run, a 228 ms garbage-collection pause on an
  overloaded laptop backed up 128 requests waiting for a pool connection and timed out two Redis
  writes. Both failures were counted, Postgres stayed correct, and the next update for each
  player repaired the boards.

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
8. **Exact country board instead of plain `ZINCRBY`.** A `+1` can't be retried safely, so after
   a wipe nothing could tell which in-flight increments a rebuild already held. The board now
   tracks the score counted per player and applies absolute scores (see above). The key has a hash
   tag, `lb:country:{tournamentId}`, so it shares a cluster slot with its bookkeeping hash.
9. **After-commit reads reuse the request's connection.** At first the rebuild read used
   `REQUIRES_NEW`. During an after-commit callback Spring still holds the request's connection, so
   each request needed a second one. In the 200-thread test, all 20 pooled connections were held
   by requests waiting for another, and joins timed out after 30 s. Propagation `REQUIRED` fixed it
   ([LeaderboardWriter](src/main/java/com/example/tournament/leaderboard/LeaderboardWriter.java)).
10. **The entry fee is stored per entry** (`group_members.entry_fee`, migration V2), so refunds
    return what each player paid, not the fee configured at 20:00.
11. **Small additions:** `GET /tournaments/current`, player search, the extra error codes above,
    named constraints (mapped to error codes), and indexes for the level-up lookup, the
    unclaimed-reward check and the end-of-day queries.

## Correctness review

Two rounds of outside review raised six correctness concerns. Each one got a test first, run
against the code as it was; all six reproduced, and each fix makes its test pass.

| Finding | Reproduced as | Fix |
|---|---|---|
| Exact ties ranked differently live and at 20:00 | Two players reach score 1 in the same ms: the board showed `[15, 14, …]`, the final ranking `[14, 15, …]`, so the one shown 1st got the 2nd-place reward | Postgres breaks exact ties by lower user id; the Redis read path re-sorts the five entries the same way; rank no longer uses `ZREVRANK` |
| A Redis write that never arrives leaves a complete board stale | A group key of the wrong type makes the write fail: the board stays behind Postgres after Redis is healthy again | Failed groups are re-applied from Postgres every 5 s; all open tournaments are re-applied every 5 min for writes lost without an error |
| The 20:00 cutoff used the arrival time | A level up and a join that arrive at 19:59:59.9 and wait on a row lock until 20:00:00.001: the level up scored, the join succeeded | The clock is read after the locks are held; joins re-check the window |
| Search reconcile compared document counts | An orphaned document hid a missing player (equal counts, nothing done); a leftover document returned `realname` for the query `oldname` | Reconcile compares player by player in both directions; search drops hits Postgres disagrees with |
| *Round 2:* the clock was read before the group lock | A transaction holds an active group's row (without ending it) from 19:59:59.9 until 20:00:00.001: the waiting level up scored with its old time | Scoring reads the clock after it holds both the player and the group row |
| *Round 2:* a failed final rebuild was never retried | A silent loss before 20:00, then a final rebuild that Redis rejects: after Redis recovers, the safety net skipped the ENDED tournament and its board stayed stale | The final rebuild is recorded in `tournaments.boards_finalized_at` (V3); the 5-minute job retries unfinalized tournaments while their boards exist |

Writing the cutoff test also showed that the join locked the player with `FOR UPDATE`, which
blocks foreign-key checks from other transactions for no reason. It now uses `FOR NO KEY UPDATE`.
The repair test showed that a Redis timeout doesn't mean the write was lost: with Redis frozen,
the command sat in its socket buffer and ran on resume, which is why every write must stay safe to
apply twice.

## Tests

```bash
./mvnw test        # needs Docker for Testcontainers
```

63 tests: unit tests for the tie-break encoding and level up, plus integration tests against real
Postgres, Redis (in cluster mode) and Elasticsearch containers. CI (GitHub Actions) runs them on
every pull request and checks that the Docker image builds.

- **Users:** create, duplicate name, validation, level up.
- **Joining:** each entry rule and error code, group fill to `ACTIVE`, same country going to
  another group, score counting only once the group is full, today's tournament existing on the
  first read.
- **Leaderboards:** order with tie-break, exact same-millisecond ties matching the final ranking,
  rank, country sums, Redis wipe recovery, key expiry.
- **Redis repair:** writes that fail are re-applied from Postgres; writes that time out but land
  late aren't counted twice; the periodic re-apply repairs updates Redis never heard about, and
  finishes a final rebuild that failed at 20:00.
- **Country board exactness:** retried and out-of-order updates, late updates after a wipe, reads
  while another instance rebuilds, half a board evicted, no empty board at startup, and 600
  concurrent level ups with Redis wiped halfway (the board still equals the Postgres sums).
- **Metrics:** error codes counted, points and filled groups counted only for real events,
  waiting-group gauges, refunds.
- **End of day:** ranks, rewards, refunds of the fee actually paid, idempotency, no points after
  20:00, claims (including 10 concurrent claims paying once), unclaimed reward blocking the next
  day.
- **Cutoff:** a level up or join that waits on the player's row across 20:00 scores nothing and
  is refused; so does a level up waiting on its group's row; a level up still waiting while the
  20:00 job ranks leaves the ranking alone.
- **Search:** prefix, exact match first, typos, country filter, limit, fresh level from Postgres,
  reconcile of players missing from the index, orphaned and wrong documents, bad parameters.
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
app, Postgres, Redis, Elasticsearch and k6 all shared that VM. Hikari pool 20, default Tomcat
settings. Every run had 0 failed requests. After the 400 and 1,500 requests/s runs, the country
board in Redis matched the Postgres sums exactly, with both failure counters at 0.

Tail latency on a laptop depends mostly on what else the machine is doing. These runs were made
while it was busy (load average about 5):

| 400 requests/s for 60 s | p50 | p90 | p99 |
|---|---|---|---|
| Level up (Postgres transaction + 2 Redis scripts) | 1.9 ms | 6.0 ms | 99.6 ms |
| Group leaderboard | 1.3 ms | 4.1 ms | 70.9 ms |
| Rank | 2.4 ms | 6.8 ms | 84.2 ms |
| Country leaderboard | 0.6 ms | 2.0 ms | 40.9 ms |

| 1,500 requests/s for 30 s | p50 | p90 | p99 |
|---|---|---|---|
| Level up | 1.4 ms | 12.0 ms | 296.6 ms |
| Group leaderboard | 0.7 ms | 5.4 ms | 272.8 ms |
| Rank | 1.4 ms | 9.2 ms | 283.4 ms |
| Country leaderboard | 0.4 ms | 3.4 ms | 142.5 ms |

For comparison, the previous version (one Redis script per level up, no exact country board, no
Elasticsearch) was run right before this one on the same busy machine. At 400 requests/s it
measured the same p50 (1.9 ms) and a worse p99 (299 ms). Earlier, on a quiet machine, it had a
p99 under 5 ms on every endpoint. The medians are stable; the tail is the laptop.

## Known limits

- **No authentication or authorization.** Any caller can level up, join or claim for any user id.
  A real deployment needs player authentication (for example a signed token whose subject is the
  user id, checked against the path) and admin-only access to operational endpoints.
- **Repair and reconcile cost grows with the data.** The 5-minute re-apply and the search
  reconcile both read every relevant row. Fine at this scale; at millions of players, re-apply
  only recently changed scores and compare search batches by checksum.
- **Rebuilds read a whole tournament at once.** A country board rebuild loads every player in a
  full group into memory and replays them in batches of 500. That's fine for tens of thousands of
  players; for millions, page through Postgres instead.
- **The country board is one hot key per tournament.** Every scoring level up touches it, so on a
  cluster that slot's node takes all country traffic. Group boards spread out.
- **The bookkeeping hash grows by one field per player in a full group** (about 50 bytes each),
  and expires with the board.
- **Elasticsearch security is off** in Compose. Turn it on, with TLS and credentials
  (`spring.elasticsearch.username`/`password`), anywhere other than a laptop.
