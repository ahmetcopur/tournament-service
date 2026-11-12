# Design

## Design decisions

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
   tracks the score counted per player and applies absolute scores (see [How the hard parts work](../README.md#how-the-hard-parts-work)). The key has a hash
   tag, `lb:country:{tournamentId}`, so it shares a cluster slot with its bookkeeping hash.
9. **After-commit reads reuse the request's connection.** At first the rebuild read used
   `REQUIRES_NEW`. During an after-commit callback Spring still holds the request's connection, so
   each request needed a second one. In the 200-thread test, all 20 pooled connections were held
   by requests waiting for another, and joins timed out after 30 s. Propagation `REQUIRED` fixed it
   ([LeaderboardWriter](../src/main/java/io/github/ahmetcopur/tournament/leaderboard/LeaderboardWriter.java)).
10. **The entry fee is stored per entry** (`group_members.entry_fee`, migration V2), so refunds
    return what each player paid, not the fee configured at 20:00.
11. **Small additions:** `GET /tournaments/current`, player search, the extra error codes in the [API](../README.md#api),
    named constraints (mapped to error codes), and indexes for the level-up lookup, the
    unclaimed-reward check and the end-of-day queries.

## Correctness review

Two rounds of AI-assisted code review raised six correctness concerns. Each one got a test first, run
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
