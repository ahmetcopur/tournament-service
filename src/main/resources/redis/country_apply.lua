-- Applies players' scores to a tournament's country board, counting each point exactly once.
--
-- KEYS[1] lb:country:{tournamentId}           sorted set: country -> total score
-- KEYS[2] lb:country:{tournamentId}:applied   hash: user id -> highest score already counted,
--                                             plus "_ready" once a full rebuild has finished
-- Both keys share the {tournamentId} hash tag, so they live in one Redis Cluster slot.
--
-- ARGV[1] expire-at (epoch seconds)
-- ARGV[2] "1" to mark the board complete after applying (end of a rebuild), else "0"
-- ARGV[3..] triples: user id, country, score (the player's absolute score, not a delta)
--
-- Only the part of a score not yet counted is added, so applying the same score twice, or an
-- older score after a newer one, changes nothing. Live updates and rebuilds can therefore
-- overlap and retry freely.
--
-- Returns 1 if the board is complete, 0 if it is not (new, wiped, or still being rebuilt).

-- The two keys only make sense together. If one is gone (evicted, deleted), drop the other.
if redis.call('EXISTS', KEYS[1]) + redis.call('EXISTS', KEYS[2]) == 1 then
  redis.call('DEL', KEYS[1], KEYS[2])
end

for i = 3, #ARGV, 3 do
  local user, country, score = ARGV[i], ARGV[i + 1], tonumber(ARGV[i + 2])
  local counted = tonumber(redis.call('HGET', KEYS[2], user) or '-1')
  if score > counted then
    redis.call('HSET', KEYS[2], user, score)
    redis.call('ZINCRBY', KEYS[1], score - math.max(counted, 0), country)
  end
end

if ARGV[2] == '1' then
  redis.call('HSET', KEYS[2], '_ready', 1)
end
redis.call('EXPIREAT', KEYS[1], ARGV[1])
redis.call('EXPIREAT', KEYS[2], ARGV[1])
return redis.call('HEXISTS', KEYS[2], '_ready')
