package network.clasher.clashercity.storage;

import network.clasher.clashercity.model.Area;
import network.clasher.clashercity.model.Phase;
import network.clasher.clashercity.model.Plot;
import network.clasher.clashercity.model.ResultRow;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * SQLite data access layer (WAL mode) for ClasherCity plots, areas, tournament entries,
 * votes, judge scores, leaderboard computation, and reward queues.
 */
public final class CityDatabase implements AutoCloseable {
    public record PendingReward(String tournamentId, int rank, String plotId) {}

    private final Connection db;

    public CityDatabase(File dbFile) throws SQLException {
        File parent = dbFile.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new SQLException("Cannot create plugin data folder: " + parent.getAbsolutePath());
        }
        this.db = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());
        initSchema();
        ensureDefaultMeta();
    }

    private void initSchema() throws SQLException {
        try (Statement st = db.createStatement()) {
            st.execute("PRAGMA journal_mode=WAL");
            st.execute("PRAGMA foreign_keys=ON");
            st.executeUpdate("CREATE TABLE IF NOT EXISTS plots (id TEXT PRIMARY KEY, world TEXT NOT NULL, min_x INTEGER NOT NULL, min_z INTEGER NOT NULL, max_x INTEGER NOT NULL, max_z INTEGER NOT NULL, price REAL NOT NULL, owner_uuid TEXT, owner_name TEXT, created_at TEXT DEFAULT CURRENT_TIMESTAMP)");
            st.executeUpdate("CREATE TABLE IF NOT EXISTS tournament_entries (tournament_id TEXT NOT NULL, plot_id TEXT NOT NULL, owner_uuid TEXT NOT NULL, owner_name TEXT NOT NULL, entered_at TEXT DEFAULT CURRENT_TIMESTAMP, PRIMARY KEY(tournament_id, plot_id))");
            st.executeUpdate("CREATE TABLE IF NOT EXISTS areas (id TEXT PRIMARY KEY, world TEXT NOT NULL, type TEXT NOT NULL, min_x INTEGER NOT NULL, min_z INTEGER NOT NULL, max_x INTEGER NOT NULL, max_z INTEGER NOT NULL)");
            st.executeUpdate("CREATE TABLE IF NOT EXISTS transactions (id INTEGER PRIMARY KEY AUTOINCREMENT, plot_id TEXT NOT NULL, player_uuid TEXT NOT NULL, player_name TEXT NOT NULL, amount REAL NOT NULL, kind TEXT NOT NULL, created_at TEXT DEFAULT CURRENT_TIMESTAMP)");
            st.executeUpdate("CREATE TABLE IF NOT EXISTS meta (key TEXT PRIMARY KEY, value TEXT NOT NULL)");
            st.executeUpdate("CREATE TABLE IF NOT EXISTS votes (tournament_id TEXT NOT NULL, voter_uuid TEXT NOT NULL, voter_name TEXT NOT NULL, plot_id TEXT NOT NULL, created_at TEXT DEFAULT CURRENT_TIMESTAMP, PRIMARY KEY(tournament_id, voter_uuid))");
            st.executeUpdate("CREATE TABLE IF NOT EXISTS judges (tournament_id TEXT NOT NULL, judge_uuid TEXT NOT NULL, judge_name TEXT NOT NULL, PRIMARY KEY(tournament_id, judge_uuid))");
            st.executeUpdate("CREATE TABLE IF NOT EXISTS scores (tournament_id TEXT NOT NULL, judge_uuid TEXT NOT NULL, plot_id TEXT NOT NULL, category TEXT NOT NULL, score INTEGER NOT NULL, updated_at TEXT DEFAULT CURRENT_TIMESTAMP, PRIMARY KEY(tournament_id, judge_uuid, plot_id, category))");
            st.executeUpdate("CREATE TABLE IF NOT EXISTS reward_queue (tournament_id TEXT NOT NULL, player_uuid TEXT NOT NULL, player_name TEXT NOT NULL, rank INTEGER NOT NULL, plot_id TEXT NOT NULL, status TEXT NOT NULL DEFAULT 'PENDING', created_at TEXT DEFAULT CURRENT_TIMESTAMP, delivered_at TEXT, PRIMARY KEY(tournament_id, player_uuid, rank))");
        }
    }

    private void ensureDefaultMeta() throws SQLException {
        if (getMeta("phase") == null) setMeta("phase", Phase.SETUP.name());
        if (getMeta("tournament_id") == null) setMeta("tournament_id", UUID.randomUUID().toString());
        if (getMeta("tournament_name") == null) setMeta("tournament_name", "ClasherCity Building Championship");
    }

    public Connection connection() { return db; }

    @Override
    public void close() throws SQLException {
        if (db != null && !db.isClosed()) db.close();
    }

    // -------------------------------------------------------------------------
    // Meta & tournament phase
    // -------------------------------------------------------------------------

    public String getMeta(String key) throws SQLException {
        try (PreparedStatement ps = db.prepareStatement("SELECT value FROM meta WHERE key=?")) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    public void setMeta(String key, String value) throws SQLException {
        try (PreparedStatement ps = db.prepareStatement("INSERT INTO meta(key,value) VALUES(?,?) ON CONFLICT(key) DO UPDATE SET value=excluded.value")) {
            ps.setString(1, key);
            ps.setString(2, value);
            ps.executeUpdate();
        }
    }

    public String tournamentId() {
        try { return getMeta("tournament_id"); }
        catch (SQLException ex) { throw new IllegalStateException(ex); }
    }

    public String tournamentName() {
        try { return Objects.requireNonNullElse(getMeta("tournament_name"), "ClasherCity Championship"); }
        catch (SQLException ex) { return "ClasherCity Championship"; }
    }

    public Phase phase() {
        try { return Phase.valueOf(Objects.requireNonNullElse(getMeta("phase"), "SETUP").toUpperCase(Locale.ROOT)); }
        catch (Exception ex) { return Phase.SETUP; }
    }

    public void setPhase(Phase phase) throws SQLException {
        setMeta("phase", phase.name());
    }

    public String startNewTournament(String name) throws SQLException {
        String newId = UUID.randomUUID().toString();
        setMeta("tournament_id", newId);
        setMeta("tournament_name", name);
        for (int rank = 1; rank <= 3; rank++) setMeta("winner_" + rank, "");
        setPhase(Phase.REGISTRATION);
        return newId;
    }

    // -------------------------------------------------------------------------
    // Plots & Areas
    // -------------------------------------------------------------------------

    public boolean plotExists(String id) throws SQLException {
        try (PreparedStatement ps = db.prepareStatement("SELECT 1 FROM plots WHERE lower(id)=lower(?)")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) { return rs.next(); }
        }
    }

    public boolean overlapsAny(String worldName, int minX, int minZ, int maxX, int maxZ) throws SQLException {
        String sql = "SELECT 1 FROM plots WHERE world=? AND NOT(max_x<? OR min_x>? OR max_z<? OR min_z>?) "
                + "UNION ALL SELECT 1 FROM areas WHERE world=? AND NOT(max_x<? OR min_x>? OR max_z<? OR min_z>?) LIMIT 1";
        try (PreparedStatement ps = db.prepareStatement(sql)) {
            ps.setString(1, worldName);
            ps.setInt(2, minX - 1); ps.setInt(3, maxX + 1);
            ps.setInt(4, minZ - 1); ps.setInt(5, maxZ + 1);
            ps.setString(6, worldName);
            ps.setInt(7, minX - 1); ps.setInt(8, maxX + 1);
            ps.setInt(9, minZ - 1); ps.setInt(10, maxZ + 1);
            try (ResultSet rs = ps.executeQuery()) { return rs.next(); }
        }
    }

    public void insertPlot(Plot plot) throws SQLException {
        try (PreparedStatement ps = db.prepareStatement("INSERT INTO plots(id,world,min_x,min_z,max_x,max_z,price) VALUES(?,?,?,?,?,?,?)")) {
            ps.setString(1, plot.id());
            ps.setString(2, plot.world());
            ps.setInt(3, plot.minX());
            ps.setInt(4, plot.minZ());
            ps.setInt(5, plot.maxX());
            ps.setInt(6, plot.maxZ());
            ps.setDouble(7, plot.price());
            ps.executeUpdate();
        }
    }

    public void deletePlotRaw(String id) throws SQLException {
        try (PreparedStatement ps = db.prepareStatement("DELETE FROM plots WHERE id=?")) {
            ps.setString(1, id);
            ps.executeUpdate();
        }
    }

    public boolean updatePlotPrice(String id, double price) throws SQLException {
        try (PreparedStatement ps = db.prepareStatement("UPDATE plots SET price=? WHERE lower(id)=lower(?)")) {
            ps.setDouble(1, price);
            ps.setString(2, id);
            return ps.executeUpdate() == 1;
        }
    }

    public void insertArea(Area area) throws SQLException {
        try (PreparedStatement ps = db.prepareStatement("INSERT INTO areas(id,world,type,min_x,min_z,max_x,max_z) VALUES(?,?,?,?,?,?,?)")) {
            ps.setString(1, area.id());
            ps.setString(2, area.world());
            ps.setString(3, area.type());
            ps.setInt(4, area.minX());
            ps.setInt(5, area.minZ());
            ps.setInt(6, area.maxX());
            ps.setInt(7, area.maxZ());
            ps.executeUpdate();
        }
    }

    public void deleteAreaRaw(String id, String type) throws SQLException {
        try (PreparedStatement ps = db.prepareStatement("DELETE FROM areas WHERE id=? AND type=?")) {
            ps.setString(1, id);
            ps.setString(2, type);
            ps.executeUpdate();
        }
    }

    public List<Plot> getPlots() throws SQLException {
        List<Plot> list = new ArrayList<>();
        try (PreparedStatement ps = db.prepareStatement("SELECT * FROM plots ORDER BY id"); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) list.add(mapPlot(rs));
        }
        return list;
    }

    public Plot getPlot(String id) throws SQLException {
        try (PreparedStatement ps = db.prepareStatement("SELECT * FROM plots WHERE lower(id)=lower(?)")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? mapPlot(rs) : null;
            }
        }
    }

    public Plot getFirstOwnedPlot(UUID ownerUuid) throws SQLException {
        try (PreparedStatement ps = db.prepareStatement("SELECT * FROM plots WHERE owner_uuid=? ORDER BY id LIMIT 1")) {
            ps.setString(1, ownerUuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? mapPlot(rs) : null;
            }
        }
    }

    public int countOwnedPlots(UUID ownerUuid) throws SQLException {
        try (PreparedStatement ps = db.prepareStatement("SELECT COUNT(*) FROM plots WHERE owner_uuid=?")) {
            ps.setString(1, ownerUuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    public Plot plotAt(String worldName, int blockX, int blockZ) throws SQLException {
        try (PreparedStatement ps = db.prepareStatement("SELECT * FROM plots WHERE world=? AND min_x<=? AND max_x>=? AND min_z<=? AND max_z>=? LIMIT 1")) {
            ps.setString(1, worldName);
            ps.setInt(2, blockX);
            ps.setInt(3, blockX);
            ps.setInt(4, blockZ);
            ps.setInt(5, blockZ);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? mapPlot(rs) : null;
            }
        }
    }

    public List<Area> getAreas() throws SQLException {
        List<Area> list = new ArrayList<>();
        try (PreparedStatement ps = db.prepareStatement("SELECT * FROM areas ORDER BY id"); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                list.add(new Area(
                        rs.getString("id"), rs.getString("world"), rs.getString("type"),
                        rs.getInt("min_x"), rs.getInt("min_z"), rs.getInt("max_x"), rs.getInt("max_z")
                ));
            }
        }
        return list;
    }

    private static Plot mapPlot(ResultSet rs) throws SQLException {
        return new Plot(
                rs.getString("id"), rs.getString("world"),
                rs.getInt("min_x"), rs.getInt("min_z"),
                rs.getInt("max_x"), rs.getInt("max_z"),
                rs.getDouble("price"), rs.getString("owner_uuid"), rs.getString("owner_name")
        );
    }

    // -------------------------------------------------------------------------
    // Tournament entries, votes, judges & scores
    // -------------------------------------------------------------------------

    public List<Plot> getTournamentPlots() throws SQLException {
        List<Plot> list = new ArrayList<>();
        String sql = "SELECT p.id,p.world,p.min_x,p.min_z,p.max_x,p.max_z,p.price,e.owner_uuid,e.owner_name "
                + "FROM plots p INNER JOIN tournament_entries e ON e.plot_id=p.id WHERE e.tournament_id=? ORDER BY p.id";
        try (PreparedStatement ps = db.prepareStatement(sql)) {
            ps.setString(1, tournamentId());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) list.add(mapPlot(rs));
            }
        }
        return list;
    }

    public boolean isTournamentEntry(String plotId) throws SQLException {
        try (PreparedStatement ps = db.prepareStatement("SELECT 1 FROM tournament_entries WHERE tournament_id=? AND lower(plot_id)=lower(?)")) {
            ps.setString(1, tournamentId());
            ps.setString(2, plotId);
            try (ResultSet rs = ps.executeQuery()) { return rs.next(); }
        }
    }

    public int countTournamentEntries(UUID ownerUuid) throws SQLException {
        try (PreparedStatement ps = db.prepareStatement("SELECT COUNT(*) FROM tournament_entries WHERE tournament_id=? AND owner_uuid=?")) {
            ps.setString(1, tournamentId());
            ps.setString(2, ownerUuid.toString());
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? rs.getInt(1) : 0; }
        }
    }

    public void addTournamentEntry(String plotId, UUID ownerUuid, String ownerName) throws SQLException {
        try (PreparedStatement ps = db.prepareStatement("INSERT INTO tournament_entries(tournament_id,plot_id,owner_uuid,owner_name) VALUES(?,?,?,?)")) {
            ps.setString(1, tournamentId());
            ps.setString(2, plotId);
            ps.setString(3, ownerUuid.toString());
            ps.setString(4, ownerName);
            ps.executeUpdate();
        }
    }

    public void recordVote(UUID voterUuid, String voterName, String plotId) throws SQLException {
        try (PreparedStatement ps = db.prepareStatement("INSERT INTO votes(tournament_id,voter_uuid,voter_name,plot_id) VALUES(?,?,?,?)")) {
            ps.setString(1, tournamentId());
            ps.setString(2, voterUuid.toString());
            ps.setString(3, voterName);
            ps.setString(4, plotId);
            ps.executeUpdate();
        }
    }

    public void addJudge(UUID judgeUuid, String judgeName) throws SQLException {
        try (PreparedStatement ps = db.prepareStatement("INSERT OR REPLACE INTO judges(tournament_id,judge_uuid,judge_name) VALUES(?,?,?)")) {
            ps.setString(1, tournamentId());
            ps.setString(2, judgeUuid.toString());
            ps.setString(3, judgeName);
            ps.executeUpdate();
        }
    }

    public void removeJudge(UUID judgeUuid) throws SQLException {
        try (PreparedStatement ps = db.prepareStatement("DELETE FROM judges WHERE tournament_id=? AND judge_uuid=?")) {
            ps.setString(1, tournamentId());
            ps.setString(2, judgeUuid.toString());
            ps.executeUpdate();
        }
    }

    public boolean isJudge(UUID judgeUuid) throws SQLException {
        try (PreparedStatement ps = db.prepareStatement("SELECT 1 FROM judges WHERE tournament_id=? AND judge_uuid=?")) {
            ps.setString(1, tournamentId());
            ps.setString(2, judgeUuid.toString());
            try (ResultSet rs = ps.executeQuery()) { return rs.next(); }
        }
    }

    public void saveScore(UUID judgeUuid, String plotId, String category, int score) throws SQLException {
        String sql = "INSERT INTO scores(tournament_id,judge_uuid,plot_id,category,score,updated_at) VALUES(?,?,?,?,?,CURRENT_TIMESTAMP) "
                + "ON CONFLICT(tournament_id,judge_uuid,plot_id,category) DO UPDATE SET score=excluded.score,updated_at=CURRENT_TIMESTAMP";
        try (PreparedStatement ps = db.prepareStatement(sql)) {
            ps.setString(1, tournamentId());
            ps.setString(2, judgeUuid.toString());
            ps.setString(3, plotId);
            ps.setString(4, category);
            ps.setInt(5, score);
            ps.executeUpdate();
        }
    }

    public List<ResultRow> calculateResults(int categoryCount, double playerWeight, double judgeWeight) throws SQLException {
        List<Plot> owned = getTournamentPlots();
        if (owned.isEmpty()) return List.of();

        Map<String, Integer> voteCounts = new HashMap<>();
        int totalVotes = 0;
        try (PreparedStatement ps = db.prepareStatement("SELECT plot_id,COUNT(*) AS n FROM votes WHERE tournament_id=? GROUP BY plot_id")) {
            ps.setString(1, tournamentId());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    int n = rs.getInt("n");
                    voteCounts.put(rs.getString("plot_id"), n);
                    totalVotes += n;
                }
            }
        }

        Map<String, List<Integer>> judgeTotals = new HashMap<>();
        String sql = "SELECT plot_id,judge_uuid,SUM(score) AS total,COUNT(*) AS categories FROM scores WHERE tournament_id=? GROUP BY plot_id,judge_uuid HAVING COUNT(*)=?";
        try (PreparedStatement ps = db.prepareStatement(sql)) {
            ps.setString(1, tournamentId());
            ps.setInt(2, categoryCount);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    judgeTotals.computeIfAbsent(rs.getString("plot_id"), k -> new ArrayList<>()).add(rs.getInt("total"));
                }
            }
        }

        double weightTotal = playerWeight + judgeWeight;
        if (weightTotal <= 0) {
            playerWeight = 50.0;
            judgeWeight = 50.0;
            weightTotal = 100.0;
        }

        List<ResultRow> result = new ArrayList<>();
        for (Plot plot : owned) {
            int votes = voteCounts.getOrDefault(plot.id(), 0);
            double playerScore = totalVotes == 0 ? 0.0 : (votes * 100.0 / totalVotes);
            List<Integer> totals = judgeTotals.getOrDefault(plot.id(), List.of());
            double judgeScore = totals.isEmpty() ? 0.0 : totals.stream().mapToInt(Integer::intValue).average().orElse(0.0);
            double finalScore = ((playerScore * playerWeight) + (judgeScore * judgeWeight)) / weightTotal;
            result.add(new ResultRow(plot.id(), plot.ownerUuid(), plot.ownerName(), votes, playerScore, judgeScore, finalScore));
        }

        result.sort(Comparator.comparingDouble(ResultRow::finalScore).reversed()
                .thenComparing(Comparator.comparingInt(ResultRow::votes).reversed())
                .thenComparing(ResultRow::plotId, String.CASE_INSENSITIVE_ORDER));

        for (int rank = 1; rank <= 3; rank++) {
            String forced = getMeta("winner_" + rank);
            if (forced == null || forced.isBlank()) continue;
            int found = -1;
            for (int i = 0; i < result.size(); i++) {
                if (result.get(i).plotId().equalsIgnoreCase(forced)) {
                    found = i;
                    break;
                }
            }
            if (found >= 0 && found != rank - 1) {
                ResultRow row = result.remove(found);
                result.add(Math.min(rank - 1, result.size()), row);
            }
        }
        return result;
    }

    // -------------------------------------------------------------------------
    // Reward queue
    // -------------------------------------------------------------------------

    public void queueReward(UUID playerUuid, String playerName, int rank, String plotId) throws SQLException {
        try (PreparedStatement ps = db.prepareStatement("INSERT OR IGNORE INTO reward_queue(tournament_id,player_uuid,player_name,rank,plot_id,status) VALUES(?,?,?,?,?,'PENDING')")) {
            ps.setString(1, tournamentId());
            ps.setString(2, playerUuid.toString());
            ps.setString(3, playerName);
            ps.setInt(4, rank);
            ps.setString(5, plotId);
            ps.executeUpdate();
        }
    }

    public List<PendingReward> pendingRewards(UUID playerUuid) throws SQLException {
        List<PendingReward> list = new ArrayList<>();
        try (PreparedStatement ps = db.prepareStatement("SELECT tournament_id,rank,plot_id FROM reward_queue WHERE player_uuid=? AND status='PENDING' ORDER BY created_at,rank")) {
            ps.setString(1, playerUuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(new PendingReward(rs.getString("tournament_id"), rs.getInt("rank"), rs.getString("plot_id")));
                }
            }
        }
        return list;
    }

    public void markRewardStatus(String tournamentId, UUID playerUuid, int rank, String status) throws SQLException {
        String sql = "DELIVERED".equals(status)
                ? "UPDATE reward_queue SET status='DELIVERED',delivered_at=CURRENT_TIMESTAMP WHERE tournament_id=? AND player_uuid=? AND rank=?"
                : "UPDATE reward_queue SET status=? WHERE tournament_id=? AND player_uuid=? AND rank=?";
        try (PreparedStatement ps = db.prepareStatement(sql)) {
            if ("DELIVERED".equals(status)) {
                ps.setString(1, tournamentId);
                ps.setString(2, playerUuid.toString());
                ps.setInt(3, rank);
            } else {
                ps.setString(1, status);
                ps.setString(2, tournamentId);
                ps.setString(3, playerUuid.toString());
                ps.setInt(4, rank);
            }
            ps.executeUpdate();
        }
    }

    public int resetRewardsForRetry(UUID playerUuid) throws SQLException {
        try (PreparedStatement ps = db.prepareStatement("UPDATE reward_queue SET status='PENDING' WHERE player_uuid=? AND status IN ('PENDING','REVIEW')")) {
            ps.setString(1, playerUuid.toString());
            return ps.executeUpdate();
        }
    }
}
