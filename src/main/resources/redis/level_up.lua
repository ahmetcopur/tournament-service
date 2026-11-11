-- One level passed by a member of an ACTIVE group.
-- KEYS[1] lb:group:{groupId}   KEYS[2] lb:country:{tournamentId}
-- ARGV[1] encoded score   ARGV[2] user id   ARGV[3] country   ARGV[4] expire-at (epoch seconds)
--
-- GT keeps the higher value, so two level ups applied out of order can't move a player back.
-- If the country board is missing (Redis wiped or expired) it is not incremented from zero;
-- the script returns 0 and the app rebuilds it from Postgres instead.
redis.call('ZADD', KEYS[1], 'GT', ARGV[1], ARGV[2])
redis.call('EXPIREAT', KEYS[1], ARGV[4])
if redis.call('EXISTS', KEYS[2]) == 0 then
  return 0
end
redis.call('ZINCRBY', KEYS[2], 1, ARGV[3])
redis.call('EXPIREAT', KEYS[2], ARGV[4])
return 1
