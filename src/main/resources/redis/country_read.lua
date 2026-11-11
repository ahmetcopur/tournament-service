-- Reads a country board, but only if it is complete; otherwise returns an empty list and the
-- app answers from Postgres.
-- KEYS[1] lb:country:{tournamentId}   KEYS[2] lb:country:{tournamentId}:applied
if redis.call('HEXISTS', KEYS[2], '_ready') == 0 then
  return {}
end
return redis.call('ZREVRANGE', KEYS[1], 0, -1, 'WITHSCORES')
