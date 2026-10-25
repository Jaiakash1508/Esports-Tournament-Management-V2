package edu.mit.league;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.io.IOException;
import java.io.InputStream;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;

/** JDBC persistence for the local MIT League application. */
public final class Database {
    private static final long DEFAULT_TOURNAMENT_ID = 1L;
    private static final Set<String> GAMES = Set.of("Valorant", "League of Legends", "Rocket League", "CS2");

    private final String jdbcUrl;
    private final String jdbcUser;
    private final String jdbcPassword;
    private final boolean mysql;
    private final Gson gson = new GsonBuilder().serializeNulls().create();
    private final ReentrantLock writeLock = new ReentrantLock();

    public Database(Path databaseFile) throws Exception {
        String configuredUrl = System.getenv("MIT_LEAGUE_DB_URL");
        mysql = configuredUrl != null && configuredUrl.startsWith("jdbc:mysql:");
        jdbcUser = System.getenv().getOrDefault("MIT_LEAGUE_DB_USER", "");
        jdbcPassword = System.getenv().getOrDefault("MIT_LEAGUE_DB_PASSWORD", "");
        if (configuredUrl != null && !configuredUrl.isBlank()) {
            if (!configuredUrl.startsWith("jdbc:mysql:")) throw new IllegalArgumentException("MIT_LEAGUE_DB_URL must use jdbc:mysql: or be unset.");
            jdbcUrl = configuredUrl;
        } else {
            Path absolute = databaseFile.toAbsolutePath();
            Files.createDirectories(absolute.getParent());
            jdbcUrl = "jdbc:sqlite:" + absolute;
        }
        initialize();
    }

    private Connection open() throws SQLException {
        Connection connection = mysql
            ? DriverManager.getConnection(jdbcUrl, jdbcUser, jdbcPassword)
            : DriverManager.getConnection(jdbcUrl);
        if (!mysql) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA foreign_keys = ON");
                statement.execute("PRAGMA busy_timeout = 5000");
            }
        }
        return connection;
    }

    private void initialize() throws SQLException {
        try (Connection connection = open()) {
            executeSqlResource(connection, mysql ? "/db/schema-mysql.sql" : "/db/schema.sql");
            try (Statement statement = connection.createStatement()) {
                statement.execute(mysql ? """
                    INSERT IGNORE INTO tournaments (tournament_id, name, season, status)
                    VALUES (1, 'MIT Fall Championship', 'Fall', 'ACTIVE')
                    """ : """
                    INSERT OR IGNORE INTO tournaments (tournament_id, name, season, status)
                    VALUES (1, 'MIT Fall Championship', 'Fall', 'ACTIVE')
                    """);
                if (!mysql) statement.execute("PRAGMA journal_mode = WAL");
            }
        }

        boolean empty;
        try (Connection connection = open(); Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM teams")) {
            empty = result.next() && result.getInt(1) == 0;
        }
        if (empty) seedSampleData();
    }

    private void executeSqlResource(Connection connection, String resource) throws SQLException {
        try (InputStream input = Database.class.getResourceAsStream(resource)) {
            if (input == null) throw new SQLException("Missing database resource: " + resource);
            String script = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            try (Statement statement = connection.createStatement()) {
                for (String sql : script.split(";")) {
                    if (sql.isBlank()) continue;
                    try {
                        statement.execute(sql.trim());
                    } catch (SQLException exception) {
                        if (!mysql || exception.getErrorCode() != 1061 || !sql.trim().startsWith("CREATE INDEX")) throw exception;
                    }
                }
            }
        } catch (IOException exception) {
            throw new SQLException("Could not read database resource: " + resource, exception);
        }
    }

    private void seedSampleData() throws SQLException {
        try (Connection connection = open()) {
            connection.setAutoCommit(false);
            try {
                executeSqlResource(connection, "/db/seed.sql");
                connection.commit();
            } catch (SQLException exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    public LeagueState getState() throws SQLException {
        LeagueState state = new LeagueState();
        Map<Long, Team> teamsById = new HashMap<>();

        try (Connection connection = open();
             PreparedStatement statement = connection.prepareStatement("""
                 SELECT t.team_id, t.name, t.college, t.game, t.roster_size,
                        p.player_name AS captain
                 FROM teams t
                 LEFT JOIN players p ON p.team_id = t.team_id AND p.is_captain = 1
                 WHERE t.tournament_id = ?
                 ORDER BY t.team_id
                 """)) {
            statement.setLong(1, DEFAULT_TOURNAMENT_ID);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    Team team = new Team();
                    team.id = result.getLong("team_id");
                    team.name = result.getString("name");
                    team.college = result.getString("college");
                    team.game = result.getString("game");
                    team.rosterSize = result.getInt("roster_size");
                    team.captain = result.getString("captain");
                    state.teams.add(team);
                    teamsById.put(team.id, team);
                }
            }
        }

        try (Connection connection = open();
             PreparedStatement statement = connection.prepareStatement("""
                 SELECT p.team_id, p.player_name
                 FROM players p
                 JOIN teams t ON t.team_id = p.team_id
                 WHERE t.tournament_id = ? AND p.is_captain = 0
                 ORDER BY p.player_id
                 """)) {
            statement.setLong(1, DEFAULT_TOURNAMENT_ID);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    Team team = teamsById.get(result.getLong("team_id"));
                    if (team != null) team.members.add(result.getString("player_name"));
                }
            }
        }

        try (Connection connection = open();
             PreparedStatement statement = connection.prepareStatement("""
                 SELECT match_id, team_a_id, team_b_id, score_a, score_b, reported
                 FROM matches WHERE tournament_id = ? ORDER BY match_id
                 """)) {
            statement.setLong(1, DEFAULT_TOURNAMENT_ID);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    Match match = new Match();
                    match.id = result.getLong("match_id");
                    match.teamAId = result.getLong("team_a_id");
                    match.teamBId = result.getLong("team_b_id");
                    int scoreA = result.getInt("score_a");
                    match.scoreA = result.wasNull() ? null : scoreA;
                    int scoreB = result.getInt("score_b");
                    match.scoreB = result.wasNull() ? null : scoreB;
                    match.reported = result.getInt("reported") == 1;
                    state.matches.add(match);
                }
            }
        }
        try (Connection connection = open(); Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT description, created_at FROM match_history ORDER BY history_id DESC LIMIT 20")) {
            while (result.next()) state.history.add(new HistoryItem(result.getString("description"), result.getString("created_at")));
        }
        return state;
    }

    /** Replace the current tournament snapshot atomically after validating relationships. */
    public void replaceState(LeagueState incoming) throws SQLException {
        replaceState(incoming, AuthService.Role.ADMIN);
    }

    public void replaceState(LeagueState incoming, AuthService.Role role) throws SQLException {
        if (role == null) throw new IllegalArgumentException("Sign in before changing tournament data.");
        if (role == AuthService.Role.CAPTAIN) throw new IllegalArgumentException("Captain accounts are view-only; only admins or register accounts can submit permitted changes.");
        validate(incoming);
        writeLock.lock();
        try {
            LeagueState previous = getState();
            if (role == AuthService.Role.REGISTER && !registrationMayAppendTeam(previous, incoming)) {
                throw new IllegalArgumentException("Register accounts can submit one new team without changing existing teams, matches, or scores.");
            }
            try (Connection connection = open()) {
            connection.setAutoCommit(false);
            try {
                try (Statement statement = connection.createStatement()) {
                    statement.executeUpdate("DELETE FROM matches");
                    statement.executeUpdate("DELETE FROM players");
                    statement.executeUpdate("DELETE FROM teams");
                }
                insertTeamsAndPlayers(connection, incoming.teams);
                insertMatches(connection, incoming.matches);
                recordMatchHistory(connection, previous, incoming);
                connection.commit();
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            }
            }
        } finally {
            writeLock.unlock();
        }
    }

    private boolean registrationMayAppendTeam(LeagueState before, LeagueState after) {
        if (after.teams.size() != before.teams.size() + 1 || before.matches.size() != after.matches.size()) return false;
        for (int i = 0; i < before.matches.size(); i++) if (!sameMatch(before.matches.get(i), after.matches.get(i))) return false;
        Map<Long, Team> updated = new HashMap<>();
        for (Team team : after.teams) updated.put(team.id, team);
        for (Team oldTeam : before.teams) {
            Team newTeam = updated.get(oldTeam.id);
            if (newTeam == null || !sameTeam(oldTeam, newTeam)) return false;
        }
        return true;
    }

    private boolean sameTeam(Team a, Team b) {
        return a.id == b.id && java.util.Objects.equals(a.name, b.name)
            && java.util.Objects.equals(a.college, b.college) && java.util.Objects.equals(a.game, b.game)
            && java.util.Objects.equals(a.captain, b.captain) && a.rosterSize == b.rosterSize
            && java.util.Objects.equals(a.members, b.members);
    }

    private boolean sameMatch(Match a, Match b) {
        return a.id == b.id && a.teamAId == b.teamAId && a.teamBId == b.teamBId
            && java.util.Objects.equals(a.scoreA, b.scoreA) && java.util.Objects.equals(a.scoreB, b.scoreB)
            && a.reported == b.reported;
    }

    private void recordMatchHistory(Connection connection, LeagueState before, LeagueState after) throws SQLException {
        Map<Long, Match> oldMatches = new HashMap<>();
        for (Match match : before.matches) oldMatches.put(match.id, match);
        Map<Long, Team> oldTeams = new HashMap<>();
        Map<Long, Team> newTeams = new HashMap<>();
        for (Team team : before.teams) oldTeams.put(team.id, team);
        for (Team team : after.teams) newTeams.put(team.id, team);
        try (PreparedStatement insert = connection.prepareStatement("INSERT INTO match_history(description) VALUES (?)")) {
            for (Match match : after.matches) {
                Match prior = oldMatches.remove(match.id);
                Team a = newTeams.get(match.teamAId), b = newTeams.get(match.teamBId);
                String description = null;
                if (prior == null) description = "Match scheduled: " + a.name + " vs " + b.name;
                else if (!java.util.Objects.equals(prior.scoreA, match.scoreA) || !java.util.Objects.equals(prior.scoreB, match.scoreB)) {
                    String action = match.reported ? (prior.reported ? "Score updated" : "Result reported") : "Score cleared";
                    description = action + ": " + a.name + " " + score(match.scoreA) + "–" + score(match.scoreB) + " " + b.name;
                }
                if (description != null) { insert.setString(1, description); insert.addBatch(); }
            }
            for (Match removed : oldMatches.values()) {
                Team a = oldTeams.get(removed.teamAId), b = oldTeams.get(removed.teamBId);
                if (a != null && b != null) { insert.setString(1, "Match cancelled: " + a.name + " vs " + b.name); insert.addBatch(); }
            }
            insert.executeBatch();
        }
        try (Statement trim = connection.createStatement()) {
            String sql = mysql
                ? "DELETE FROM match_history WHERE history_id NOT IN (SELECT history_id FROM (SELECT history_id FROM match_history ORDER BY history_id DESC LIMIT 200) recent)"
                : "DELETE FROM match_history WHERE history_id NOT IN (SELECT history_id FROM match_history ORDER BY history_id DESC LIMIT 200)";
            trim.executeUpdate(sql);
        }
    }

    private String score(Integer value) { return value == null ? "–" : value.toString(); }

    public String toJson(LeagueState state) {
        return gson.toJson(state);
    }

    public String engine() { return mysql ? "MySQL" : "SQLite"; }

    public LeagueState fromJson(String json) {
        LeagueState state = gson.fromJson(json, LeagueState.class);
        if (state == null) throw new IllegalArgumentException("A JSON state object is required.");
        return state;
    }

    private void insertTeamsAndPlayers(Connection connection, List<Team> teams) throws SQLException {
        try (PreparedStatement teamInsert = connection.prepareStatement("""
                 INSERT INTO teams (team_id, tournament_id, name, college, game, roster_size)
                 VALUES (?, ?, ?, ?, ?, ?)
                 """);
             PreparedStatement playerInsert = connection.prepareStatement("""
                 INSERT INTO players (team_id, player_name, is_captain) VALUES (?, ?, ?)
                 """)) {
            for (Team team : teams) {
                teamInsert.setLong(1, team.id);
                teamInsert.setLong(2, DEFAULT_TOURNAMENT_ID);
                teamInsert.setString(3, team.name.trim());
                teamInsert.setString(4, team.college.trim());
                teamInsert.setString(5, team.game);
                teamInsert.setInt(6, team.rosterSize);
                teamInsert.executeUpdate();

                playerInsert.setLong(1, team.id);
                playerInsert.setString(2, team.captain.trim());
                playerInsert.setInt(3, 1);
                playerInsert.executeUpdate();
                for (String member : team.members) {
                    playerInsert.setLong(1, team.id);
                    playerInsert.setString(2, member.trim());
                    playerInsert.setInt(3, 0);
                    playerInsert.executeUpdate();
                }
            }
        }
    }

    private void insertMatches(Connection connection, List<Match> matches) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                 INSERT INTO matches
                   (match_id, tournament_id, team_a_id, team_b_id, score_a, score_b, reported)
                 VALUES (?, ?, ?, ?, ?, ?, ?)
                 """)) {
            for (Match match : matches) {
                statement.setLong(1, match.id);
                statement.setLong(2, DEFAULT_TOURNAMENT_ID);
                statement.setLong(3, match.teamAId);
                statement.setLong(4, match.teamBId);
                if (match.scoreA == null) statement.setNull(5, java.sql.Types.INTEGER);
                else statement.setInt(5, match.scoreA);
                if (match.scoreB == null) statement.setNull(6, java.sql.Types.INTEGER);
                else statement.setInt(6, match.scoreB);
                statement.setInt(7, match.reported ? 1 : 0);
                statement.executeUpdate();
            }
        }
    }

    private void validate(LeagueState state) {
        if (state.teams == null || state.matches == null) {
            throw new IllegalArgumentException("Teams and matches must both be arrays.");
        }
        if (state.teams.size() > 1000 || state.matches.size() > 5000) {
            throw new IllegalArgumentException("This tournament has reached the demo size limit.");
        }

        Map<Long, Team> teamsById = new HashMap<>();
        Set<String> names = new HashSet<>();
        for (Team team : state.teams) {
            if (team == null) throw new IllegalArgumentException("A team entry is invalid.");
            team.members = team.members == null ? new ArrayList<>() : team.members;
            requireText(team.name, "Team name", 60);
            requireText(team.college, "Club or college", 80);
            requireText(team.captain, "Captain name", 60);
            if (team.id < 1 || teamsById.put(team.id, team) != null) {
                throw new IllegalArgumentException("Team IDs must be unique positive numbers.");
            }
            if (team.game == null || !GAMES.contains(team.game)) {
                throw new IllegalArgumentException("Choose a supported game.");
            }
            if (team.rosterSize < 1 || team.rosterSize > 10) {
                throw new IllegalArgumentException("Roster size must be between 1 and 10.");
            }
            String teamKey = team.game.toLowerCase(Locale.ROOT) + "\u0000" + team.name.trim().toLowerCase(Locale.ROOT);
            if (!names.add(teamKey)) throw new IllegalArgumentException("That team name is already registered for this game.");
            if (team.members.size() + 1 > team.rosterSize) {
                throw new IllegalArgumentException("The named players exceed the team's roster size.");
            }
            Set<String> playerNames = new HashSet<>();
            playerNames.add(team.captain.trim().toLowerCase(Locale.ROOT));
            for (String member : team.members) {
                requireText(member, "Player name", 60);
                if (!playerNames.add(member.trim().toLowerCase(Locale.ROOT))) {
                    throw new IllegalArgumentException("Player names must be unique within a team.");
                }
            }
        }

        Set<Long> matchIds = new HashSet<>();
        for (Match match : state.matches) {
            if (match == null || match.id < 1 || !matchIds.add(match.id)) {
                throw new IllegalArgumentException("Match IDs must be unique positive numbers.");
            }
            Team a = teamsById.get(match.teamAId);
            Team b = teamsById.get(match.teamBId);
            if (a == null || b == null || a.id == b.id) {
                throw new IllegalArgumentException("Each match must include two different registered teams.");
            }
            if (!a.game.equals(b.game)) throw new IllegalArgumentException("Teams in a match must play the same game.");
            if (invalidScore(match.scoreA) || invalidScore(match.scoreB)) {
                throw new IllegalArgumentException("Scores must be whole numbers from 0 to 10000.");
            }
            boolean hasBothScores = match.scoreA != null && match.scoreB != null;
            if (match.reported != hasBothScores) {
                throw new IllegalArgumentException("A match is reported only after both scores are entered.");
            }
        }
    }

    private boolean invalidScore(Integer score) {
        return score != null && (score < 0 || score > 10000);
    }

    private void requireText(String value, String label, int maxLength) {
        if (value == null || value.isBlank() || value.trim().length() > maxLength) {
            throw new IllegalArgumentException(label + " is required and must be at most " + maxLength + " characters.");
        }
    }

    public static final class LeagueState {
        public List<Team> teams = new ArrayList<>();
        public List<Match> matches = new ArrayList<>();
        public List<HistoryItem> history = new ArrayList<>();
    }

    public static final class HistoryItem {
        public String description;
        public String createdAt;
        public HistoryItem(String description, String createdAt) { this.description = description; this.createdAt = createdAt; }
    }

    public static final class Team {
        public long id;
        public String name;
        public String college;
        public String game;
        public String captain;
        public int rosterSize;
        public List<String> members = new ArrayList<>();
    }

    public static final class Match {
        public long id;
        public long teamAId;
        public long teamBId;
        public Integer scoreA;
        public Integer scoreB;
        public boolean reported;
    }
}
