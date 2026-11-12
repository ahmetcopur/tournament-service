-- Applies players' scores to ONE slice of a tournament's country board, counting each point once.
--
-- KEYS[1]    totals   lb:country:{t7:s3/8}         sorted set: country -> this slice's total
-- KEYS[2]    meta     lb:country:{t7:s3/8}:meta    hash: epoch, ready, final
-- KEYS[3..]  buckets  lb:country:{t7:s3/8}:b<n>    hash: __e -> epoch, user id -> highest score counted
-- All keys share one hash tag, so they live in one Redis Cluster slot.
--
-- ARGV[1] expire-at (epoch seconds)
-- ARGV[2] "1" to mark the slice complete after applying (end of a rebuild), else "0"
-- ARGV[3] countries to make sure exist on the board, comma separated, or ""
-- ARGV[4] number of buckets k, then ARGV[5 .. 4+k] are their ids (same order as KEYS[3..])
-- then quadruples: bucket position (1..k), user id, country, score (absolute, not a delta)
--
-- Only the part of a score not yet counted is added, so applying the same score twice, or an
-- older score after a newer one, changes nothing: live updates, retries and rebuilds can overlap.
--
-- Returns 1 if the slice is complete, 0 if it is not (new, lost, or being rebuilt).

local totals, meta = KEYS[1], KEYS[2]

-- After 20:00 the board is final and its bookkeeping is gone; nothing may change it.
if redis.call('HGET', meta, 'final') == '1' then
  return 1
end

-- The totals and the meta hash only make sense together. If either is missing, start a new
-- epoch: bookkeeping from older epochs is then ignored (and reset on first use) instead of
-- being trusted against totals it no longer matches.
local epoch = redis.call('HGET', meta, 'epoch')
local hasTotals = redis.call('EXISTS', totals) == 1
if (not epoch) or (not hasTotals) then
  if epoch or hasTotals then
    redis.call('DEL', totals, meta)
  end
  local now = redis.call('TIME')
  epoch = now[1] .. string.format('%06d', tonumber(now[2]))
  redis.call('HSET', meta, 'epoch', epoch)
end

local k = tonumber(ARGV[4])
for i = 1, k do
  local bucket = KEYS[2 + i]
  if redis.call('HGET', bucket, '__e') ~= epoch then
    redis.call('DEL', bucket)
    redis.call('HSET', bucket, '__e', epoch)
  end
end

for j = 5 + k, #ARGV, 4 do
  local bucket = KEYS[2 + tonumber(ARGV[j])]
  local user, country, score = ARGV[j + 1], ARGV[j + 2], tonumber(ARGV[j + 3])
  local counted = tonumber(redis.call('HGET', bucket, user) or '-1')
  if score > counted then
    redis.call('HSET', bucket, user, score)
    redis.call('ZINCRBY', totals, score - math.max(counted, 0), country)
  end
end

if ARGV[3] ~= '' then
  for country in string.gmatch(ARGV[3], '[^,]+') do
    redis.call('ZINCRBY', totals, 0, country)
  end
end
if ARGV[2] == '1' then
  redis.call('HSET', meta, 'ready', '1')
end

redis.call('EXPIREAT', totals, ARGV[1])
redis.call('EXPIREAT', meta, ARGV[1])
for i = 1, k do
  redis.call('EXPIREAT', KEYS[2 + i], ARGV[1])
end
if redis.call('HGET', meta, 'ready') == '1' then
  return 1
end
return 0
