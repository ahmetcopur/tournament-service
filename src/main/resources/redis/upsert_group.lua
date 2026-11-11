-- Write group members without ever lowering a value that is already there.
-- Used when a group turns ACTIVE and when rebuilding a board from Postgres.
-- KEYS[1] lb:group:{groupId}
-- ARGV[1] expire-at (epoch seconds), then pairs: encoded score, user id
for i = 2, #ARGV, 2 do
  redis.call('ZADD', KEYS[1], 'GT', ARGV[i], ARGV[i + 1])
end
redis.call('EXPIREAT', KEYS[1], ARGV[1])
return 1
