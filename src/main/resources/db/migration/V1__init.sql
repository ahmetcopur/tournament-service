-- Schema from the design doc. Constraint names are explicit so the API can map a
-- violation to the right error code, and three lookup indexes are added for the
-- hot paths (level up, entry checks, end of day).

CREATE TABLE users (
    id          BIGSERIAL PRIMARY KEY,
    username    VARCHAR(32) NOT NULL CONSTRAINT uq_users_username UNIQUE,
    country     CHAR(2)     NOT NULL,
    level       INT         NOT NULL DEFAULT 1,
    coins       BIGINT      NOT NULL DEFAULT 5000 CONSTRAINT ck_users_coins_non_negative CHECK (coins >= 0),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE tournaments (
    id         BIGSERIAL PRIMARY KEY,
    starts_at  TIMESTAMPTZ NOT NULL CONSTRAINT uq_tournaments_starts_at UNIQUE,
    ends_at    TIMESTAMPTZ NOT NULL,
    status     VARCHAR(16) NOT NULL CHECK (status IN ('ACTIVE', 'ENDED'))
);

CREATE TABLE tournament_groups (
    id             BIGSERIAL PRIMARY KEY,
    tournament_id  BIGINT      NOT NULL REFERENCES tournaments(id),
    status         VARCHAR(16) NOT NULL CHECK (status IN ('WAITING', 'ACTIVE', 'ENDED')),
    member_count   INT         NOT NULL DEFAULT 0,
    started_at     TIMESTAMPTZ
);

CREATE TABLE group_members (
    id                BIGSERIAL PRIMARY KEY,
    group_id          BIGINT      NOT NULL REFERENCES tournament_groups(id),
    tournament_id     BIGINT      NOT NULL REFERENCES tournaments(id),
    user_id           BIGINT      NOT NULL REFERENCES users(id),
    country           CHAR(2)     NOT NULL,
    score             INT         NOT NULL DEFAULT 0,
    score_updated_at  TIMESTAMPTZ,
    final_rank        INT,
    reward_coins      INT         NOT NULL DEFAULT 0,
    reward_claimed    BOOLEAN     NOT NULL DEFAULT false,
    CONSTRAINT uq_member_group_country     UNIQUE (group_id, country),
    CONSTRAINT uq_member_tournament_user   UNIQUE (tournament_id, user_id)
);

CREATE INDEX idx_groups_waiting    ON tournament_groups (tournament_id) WHERE status = 'WAITING';
CREATE INDEX idx_groups_tournament ON tournament_groups (tournament_id);
CREATE INDEX idx_members_user      ON group_members (user_id);
CREATE INDEX idx_members_unclaimed ON group_members (user_id) WHERE reward_coins > 0 AND reward_claimed = false;
