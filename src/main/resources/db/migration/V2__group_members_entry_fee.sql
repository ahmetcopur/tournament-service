-- The fee each player actually paid, so a refund for an unfilled group returns exactly that,
-- even if game.tournament.entry-fee is changed during the day. Every entry made before this
-- migration paid the original 1,000; new rows must say what they paid.
ALTER TABLE group_members
    ADD COLUMN entry_fee BIGINT NOT NULL DEFAULT 1000 CONSTRAINT ck_member_entry_fee CHECK (entry_fee >= 0);
ALTER TABLE group_members ALTER COLUMN entry_fee DROP DEFAULT;
