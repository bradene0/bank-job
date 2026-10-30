-- Scoring by level (docs/security-and-levels.md): a session earns a level's points once, with
-- the first win on it, plus 10 per nerve it had left. The haul (stolen_cents) breaks ties.
CREATE TABLE leaderboard_levels (
    session_id uuid        NOT NULL REFERENCES sessions (id),
    level      smallint    NOT NULL CHECK (level BETWEEN 1 AND 5),
    points     integer     NOT NULL CHECK (points >= 0),
    cracked_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (session_id, level)
);

ALTER TABLE leaderboard
    ADD COLUMN levels_cracked smallint NOT NULL DEFAULT 0 CHECK (levels_cracked BETWEEN 0 AND 5);

-- Old scores (dollars x multiplier, minus attempts) cannot be converted: they kept no per-level
-- record. The leaderboard is a projection of game data, so it starts over under the new rule.
DELETE FROM leaderboard;

DROP INDEX leaderboard_ranked_idx;
CREATE INDEX leaderboard_ranked_idx ON leaderboard (score DESC, stolen_cents DESC)
    WHERE best_level IS NOT NULL;
