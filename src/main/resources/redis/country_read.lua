-- Reads one slice of a country board, but only if it is complete; otherwise returns an empty list
-- and the app answers from Postgres.
-- KEYS[1] lb:country:{t7:s3/8}   KEYS[2] lb:country:{t7:s3/8}:meta
if redis.call('HGET', KEYS[2], 'ready') ~= '1' then
  return {}
end
return redis.call('ZRANGE', KEYS[1], 0, -1, 'WITHSCORES')
