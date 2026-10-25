CREATE TABLE IF NOT EXISTS tournaments (
    tournament_id INTEGER PRIMARY KEY,
    name TEXT NOT NULL,
    season TEXT NOT NULL,
    status TEXT NOT NULL DEFAULT 'ACTIVE'
);

CREATE TABLE IF NOT EXISTS teams (
    team_id INTEGER PRIMARY KEY,
    tournament_id INTEGER NOT NULL,
    name TEXT NOT NULL COLLATE NOCASE,
    college TEXT NOT NULL,
    game TEXT NOT NULL,
    roster_size INTEGER NOT NULL CHECK (roster_size BETWEEN 1 AND 10),
    FOREIGN KEY (tournament_id) REFERENCES tournaments(tournament_id) ON DELETE CASCADE,
    UNIQUE (tournament_id, game, name)
);

CREATE TABLE IF NOT EXISTS players (
    player_id INTEGER PRIMARY KEY AUTOINCREMENT,
    team_id INTEGER NOT NULL,
    player_name TEXT NOT NULL COLLATE NOCASE,
    is_captain INTEGER NOT NULL DEFAULT 0 CHECK (is_captain IN (0, 1)),
    FOREIGN KEY (team_id) REFERENCES teams(team_id) ON DELETE CASCADE,
    UNIQUE (team_id, player_name)
);

CREATE TABLE IF NOT EXISTS matches (
    match_id INTEGER PRIMARY KEY,
    tournament_id INTEGER NOT NULL,
    team_a_id INTEGER NOT NULL,
    team_b_id INTEGER NOT NULL,
    score_a INTEGER,
    score_b INTEGER,
    reported INTEGER NOT NULL DEFAULT 0 CHECK (reported IN (0, 1)),
    CHECK (team_a_id <> team_b_id),
    CHECK (score_a IS NULL OR score_a BETWEEN 0 AND 10000),
    CHECK (score_b IS NULL OR score_b BETWEEN 0 AND 10000),
    CHECK (reported = 0 OR (score_a IS NOT NULL AND score_b IS NOT NULL)),
    FOREIGN KEY (tournament_id) REFERENCES tournaments(tournament_id) ON DELETE CASCADE,
    FOREIGN KEY (team_a_id) REFERENCES teams(team_id) ON DELETE CASCADE,
    FOREIGN KEY (team_b_id) REFERENCES teams(team_id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS match_history (
    history_id INTEGER PRIMARY KEY AUTOINCREMENT,
    description TEXT NOT NULL,
    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_teams_tournament ON teams(tournament_id);
CREATE INDEX IF NOT EXISTS idx_players_team ON players(team_id);
CREATE INDEX IF NOT EXISTS idx_matches_tournament ON matches(tournament_id);
