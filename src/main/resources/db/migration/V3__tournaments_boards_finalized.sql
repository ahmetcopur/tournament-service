-- When the Redis boards of a finished tournament were rebuilt from its final Postgres values.
-- NULL on an ENDED tournament means that rebuild hasn't succeeded yet (Redis was down at 20:00,
-- or the instance died), and the leaderboard job keeps retrying it until the boards expire.
-- Existing ENDED rows stay NULL: rebuilding them once more is harmless.
ALTER TABLE tournaments ADD COLUMN boards_finalized_at TIMESTAMPTZ;
