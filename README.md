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

Secrets come from a git-ignored `.env` file; [.env.example](.env.example) lists them with
local-only defaults. Set real values anywhere other than a laptop, above all `AUTH_TOKEN_SECRET`.

| Port | What | Reachable from |
|---|---|---|
| 8080 | The API | anywhere |
| 8081 | Actuator: health, metrics, Prometheus | this machine only (`127.0.0.1`); keep it internal |
| 3000 / 9090 | Grafana dashboard (home page; `admin`/`admin` edits) / Prometheus | anywhere |
| 9200 | Elasticsearch, HTTPS with a password | this machine only |
| 5433 | Postgres (5433 so it doesn't clash with a local one) | anywhere |

A quick tour. Sign up returns a token; every other request sends it.

```bash
TOKEN=$(curl -s -XPOST localhost:8080/users -H 'Content-Type: application/json' -d '{"username":"alice"}' | jq -r .token)
curl -s -XPOST localhost:8080/users/1/levels -H "Authorization: Bearer $TOKEN"
curl -s localhost:8080/tournaments/current -H "Authorization: Bearer $TOKEN"
curl -s -XPOST localhost:8080/tournaments/current/entries -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -d '{"userId":1}'
```

(Joining needs level 20, so level up 19 times first.)

From an IDE (JDK 21), start the backing services, take Elasticsearch's CA certificate out of
its volume, and point the app at them:

```bash
docker compose up -d postgres redis es-setup elasticsearch
mkdir -p certs && docker compose cp elasticsearch:/usr/share/elasticsearch/config/certs/ca/ca.crt certs/ca.crt
ELASTICSEARCH_URIS=https://localhost:9200 SPRING_ELASTICSEARCH_USERNAME=tournament_app \
  SPRING_ELASTICSEARCH_PASSWORD=app-local-only SPRING_ELASTICSEARCH_RESTCLIENT_SSL_BUNDLE=elasticsearch \
  SPRING_SSL_BUNDLE_PEM_ELASTICSEARCH_TRUSTSTORE_CERTIFICATE=file:certs/ca.crt ./mvnw spring-boot:run
```

## API

`User` is `{id, username, country, level, coins}`. Errors are `{code, message}`. Every request
except sign up needs `Authorization: Bearer <token>`. **Self** means the token must be for that
very player; **player** means any valid token.

| Method | Path | Body | Who | Returns |
|---|---|---|---|---|
| POST | `/users` | `{username}` | anyone | `User` + `token`, `tokenExpiresAt` (201) |
| GET | `/users/{id}` | | self | `User` |
| POST | `/users/{id}/levels` | | self | `User` after +1 level, +25 coins |
| POST | `/tournaments/current/entries` | `{userId}` | self | `{groupId, status, leaderboard}` (201) |
| POST | `/users/{id}/rewards/claim` | | self | `User` with the reward added |
| GET | `/groups/{groupId}/leaderboard` | | player | `[{userId, username, country, score}]` |
| GET | `/tournaments/{id}/users/{userId}/rank` | | player | `{rank}` inside the user's group |
| GET | `/tournaments/{id}/countries/leaderboard` | | player | `[{country, score}]` |
| GET | `/tournaments/current` | | player | `{id, startsAt, endsAt, status, open}`; creates today's row if needed *(added)* |
| GET | `/users/search?q=ahm&country=TR&limit=20` | | player | `[User]`, best match first *(added)* |

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
| `UNAUTHORIZED` | 401 | *(added)* Missing, invalid or expired token |
| `FORBIDDEN` | 403 | *(added)* Acting as another player (whether or not that id exists) |
| `GROUP_NOT_FOUND`, `TOURNAMENT_NOT_FOUND`, `NOT_IN_TOURNAMENT` | 404 | *(added)* Unknown ids on leaderboard reads |
| `VALIDATION_ERROR` | 400 | *(added)* Bad body, path or query |
| `SEARCH_UNAVAILABLE` | 503 | *(added)* Elasticsearch can't be reached; everything else still works |

Metrics are at `GET /actuator/prometheus` on the management port (8081); see
[Monitoring](#monitoring).

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
| Country board slices | `leaderboard.country-shards` | 8 |
| Country board reuse | `leaderboard.country-board-cache` | 1 second |
| Redis repair | `leaderboard.retry-every` / `reapply-every` | 5 seconds / 5 minutes |
| Player tokens | `auth.token-ttl` (key: `AUTH_TOKEN_SECRET`) | 30 days |
| Search index check | `search.reconcile-every` | every 10 minutes |

## How the hard parts work

**Authentication** ([SecurityConfig](src/main/java/com/example/tournament/security/SecurityConfig.java)).
Sign up returns a signed token (HS256, 30 days) whose subject is the user id. Spring Security's
OAuth2 resource server checks signature, issuer and expiry on every request, statelessly. Player
actions then check that the token's subject is the player in the path or body; another player's
id gets 403 whether or not it exists, so ids can't be probed. Operational endpoints are served only
on the management port, and refused on the public port even with a valid token. Moving to a real
identity provider replaces one `JwtDecoder` bean.

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
  through the same script, a page of 500 at a time, then marks the board complete. Every update
  committed before a page was read is in the replay, and every later one applies itself, so the
  board ends up exact. Rebuilds run one at a time per tournament (a Redis lock), and until a board
  is complete, reads come from Postgres.
- *Country board layout* ([CountryBoardLayout](src/main/java/com/example/tournament/leaderboard/CountryBoardLayout.java)).
  The board is split into 8 slices by user id, each with its own hash tag, so country traffic
  spreads over 8 Redis Cluster slots; reads add the slices up (and reuse the sum for a second).
  Within a slice, the per-player bookkeeping lives in hashes of about 100 players. Hashes that
  small use Redis's compact encoding: 6.8 bytes per player against 48.3 for one big hash, measured
  in a test. Once a tournament is final the bookkeeping is deleted. Each slice has an epoch: if its
  totals or metadata are lost, a new epoch starts and older bookkeeping is ignored rather than
  trusted. Run Redis with `maxmemory-policy noeviction` (the default), since a single bookkeeping
  hash evicted on its own would go unnoticed.
- *Missed writes.* Idempotency makes retries safe, but something has to retry. When a Redis write
  fails after the commit, its group is remembered and re-applied from Postgres every 5 seconds
  until it succeeds. Writes lost without anyone seeing an error, for example an instance dying
  right after its commit, are covered by a re-apply every 5 minutes (one instance does it, via a
  lock that expires). It sends only scores changed and groups filled since its previous run, plus a
  minute of margin; the first run, or one after Redis lost data, does everything. A timeout doesn't
  even mean the write was lost: it can land late, and the retry applying it again changes nothing.
- At 20:00 and at startup, boards are rebuilt from Postgres. A successful final rebuild is recorded
  in `tournaments.boards_finalized_at`. If it fails (Redis down at 20:00, or the instance dies), the
  tournament stays unfinalized and the 5-minute job retries it until it succeeds, for as long as its
  boards would still exist. Only those tournaments are retried, never the whole history.

**Redis Cluster.** Every command touches one key, or keys sharing a hash tag: a country board
slice `lb:country:{t7:s3/8}` and its bookkeeping (`…:meta`, `…:b<n>`) sit in one slot, so the
script can update them atomically. Group boards and the 8 slices spread across nodes.
The tests run Redis in cluster mode, which rejects any cross-slot command, so the suite checks this
on every run. Switching is configuration only: set `spring.data.redis.cluster.nodes`.

**Player search** ([PlayerSearchService](src/main/java/com/example/tournament/search/PlayerSearchService.java)).
Elasticsearch holds only what people search by (id, username, country), written once at sign up
after the commit. Results rank the exact name first, then names starting with the query
(edge n-grams), then names within a typo or two (fuzzy). Level and coins come fresh from Postgres
for the ids found, so level ups never touch Elasticsearch, and a hit is returned only if Postgres
agrees on who the player is. A failed index write never fails a sign up. At startup and every
10 minutes, a reconcile job makes the index match Postgres by identity, not by count, without
reading every player. Each document carries a fingerprint (a hash of id, username and country,
computed identically in Postgres). For each range of 10,000 ids, one Elasticsearch aggregation and
one Postgres `GROUP BY` give count and fingerprint sum. Only ranges that differ are compared player
by player, re-indexing what's missing or wrong and deleting orphans. In sync, a run is two queries.
The app talks to Elasticsearch over TLS as `tournament_app`, a user that may only touch the
`players` index. If Elasticsearch is down, only search returns 503.

**Ties.** A Redis sorted set holds one double per member, so score and time are packed into one
value: `score × 10^9 + (10^9 − 1 − ms since tournament start)`. A higher score always wins, and
with equal scores the earlier time wins
([LeaderboardScore](src/main/java/com/example/tournament/leaderboard/LeaderboardScore.java)). Two
players can still reach the same score in the same millisecond. Redis would order them by member
string, so `"15"` before `"14"`. The ranking breaks such ties by the lower user id, and because a
group board has only five entries, the read path re-sorts them the same way. The live board, the
rank endpoint and the 20:00 ranking always agree.

## Monitoring

Docker Compose runs Prometheus (scraping the app's management port every 5 s) and Grafana with a provisioned
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

## Scaling and security

The known limits from the previous version, and what closed each one:

| Limit | Fix | Evidence |
|---|---|---|
| No authentication | Signed player tokens; self-only player actions; actuator only on an internal port | `AuthIntegrationTest`: missing, tampered, expired and foreign tokens get 401; acting as another player gets 403 |
| Repair re-read everything | The 5-minute re-apply sends only changes since its last run | A test changes two rows: the recent one is sent, the one unchanged since the last run is not |
| Search reconcile read everything | Per-range count and fingerprint checksums; detail only where they differ | In sync: 0 of 3 ranges examined; one wrong document: 1 range |
| Rebuilds loaded a whole tournament | Paged, 500 rows at a time, as values, not JPA entities | A 2,000-player rebuild across four pages matches Postgres |
| One hot country key | 8 slices with their own hash tags, summed on read (cached 1 s) | Five players land in five slices; the API total equals Postgres |
| Bookkeeping ~50 B/player, kept 2 days | 100-player hashes in compact encoding, deleted when final | 6.8 vs 48.3 bytes per player; 0 after 20:00 |
| Elasticsearch security off | TLS with its own CA, a password, and a least-privilege app user | Against Compose: plain HTTP refused; no credentials 401; the app user gets 403 on other indices |

## Tests

```bash
./mvnw test        # needs Docker for Testcontainers
```

75 tests: unit tests for the tie-break encoding and level up, plus integration tests against real
Postgres, Redis (in cluster mode) and Elasticsearch containers. CI (GitHub Actions) runs them on
every pull request and checks that the Docker image builds.

- **Users:** create, duplicate name, validation, level up.
- **Authentication:** sign-up token works for its player; missing, malformed, tampered, expired and
  wrongly signed tokens get 401; another player's id gets 403 on every player action; actuator is
  refused on the public port.
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
  reconcile of players missing from the index, orphaned and wrong documents, identical fingerprints
  in Java and Postgres, only differing ranges examined, bad parameters.
- **Scale:** a 2,000-player tournament rebuilt page by page; bookkeeping memory against one plain
  hash and gone after finalization; the periodic re-apply sending only recent changes; the board
  split across slices.
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

The script signs up its players and sends each one's token. Results from 2026-11-12, on an Apple
Silicon laptop: Docker Desktop with 8 CPUs and 4 GB, shared by the app, Postgres, Redis,
Elasticsearch (with TLS) and k6. Hikari pool 20, default Tomcat settings, 400 requests/s for
60 s. Every run had 0 failed requests, and afterwards the Redis country board, summed over its
slices, matched the Postgres totals exactly.

The laptop was busy (load average 6 to 9), and tail latency follows the machine more than the
code. So the previous version (on `main`, without authentication, slices or checksums) and this
one were run alternately, each on a fresh database:

| Level up | p50 | p99 |
|---|---|---|
| previous version, run 1 | 1.9 ms | 209 ms |
| previous version, run 2 | 1.6 ms | 243 ms |
| this version, run 1 | 2.2 ms | 168 ms |
| this version, run 2 | 1.5 ms | 10 ms |

Both ranges overlap: authentication and the sliced board cost nothing measurable. With the
1-second cache, the country board's p50 stays at 0.3 to 0.5 ms despite its 8 slices. Running
Prometheus and Grafana on the same 4 GB VM roughly doubles the tail (a third run with them up:
p99 421 ms).

## Known limits

- **Tokens can't be revoked or refreshed.** They are stateless and live 30 days; there is no
  password, so a lost token means a lost account. A real game would use an identity provider
  (Sign in with Apple/Google, a device account) and short tokens; that swaps the `JwtDecoder`.
- **One shared signing key.** HS256 means every instance holds the key that issues tokens. With an
  identity provider, use its public keys (RS256/ES256) so the API can only verify.
- **Redis must not evict** (`maxmemory-policy noeviction`, the default). Losing a slice's totals or
  metadata is detected and rebuilt; losing a single bookkeeping hash on its own is not.
- **Postgres and Redis run without TLS or a Redis password** in Compose. Fine on one machine;
  anywhere else, use managed services or turn both on.
- **The search checksum still scans the users table** in Postgres (one `GROUP BY` per run). That is
  cheap in the database, and nothing crosses the network unless a range differs.
- **A rebuild of a huge tournament can outlive its 60-second lock**, and a second instance may then
  rebuild in parallel. That is safe, because every write is idempotent, but it is duplicate work.
