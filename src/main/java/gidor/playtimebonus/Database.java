package gidor.playtimebonus;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * SQLite storage. Everything runs on ONE dedicated daemon thread, so the main thread never
 * waits on disk I/O and the single connection is never used concurrently.
 * (Paper/Spigot ship the sqlite-jdbc driver, nothing needs to be shaded.)
 */
public final class Database {

    private final Logger log;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "PlayBonus-DB");
        t.setDaemon(true);
        return t;
    });
    private Connection conn;

    public Database(Logger log) {
        this.log = log;
    }

    /** Blocks briefly once at startup. */
    public void init(File file) throws Exception {
        executor.submit(() -> {
            try {
                Class.forName("org.sqlite.JDBC");
                conn = DriverManager.getConnection("jdbc:sqlite:" + file.getAbsolutePath());
                try (Statement st = conn.createStatement()) {
                    st.execute("PRAGMA journal_mode=WAL");
                    st.execute("PRAGMA synchronous=NORMAL");
                    st.execute("""
                            CREATE TABLE IF NOT EXISTS players (
                                uuid TEXT PRIMARY KEY,
                                name TEXT NOT NULL,
                                playtime_minutes INTEGER NOT NULL DEFAULT 0,
                                last_daily_day INTEGER
                            )""");
                }
                return null;
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }).get(15, TimeUnit.SECONDS);
    }

    public CompletableFuture<PlayerData> load(UUID uuid, String name) {
        return CompletableFuture.supplyAsync(() -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT playtime_minutes, last_daily_day FROM players WHERE uuid = ?")) {
                ps.setString(1, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        long minutes = rs.getLong(1);
                        long daily = rs.getLong(2);
                        if (rs.wasNull()) daily = PlayerData.NEVER;
                        return new PlayerData(uuid, name, minutes, daily);
                    }
                }
                return new PlayerData(uuid, name, 0, PlayerData.NEVER);
            } catch (SQLException e) {
                throw new IllegalStateException(e);
            }
        }, executor);
    }

    /** Batch upsert in a single transaction. */
    public void save(List<PlayerData.Snapshot> snapshots) {
        if (snapshots.isEmpty()) return;
        executor.execute(() -> {
            try (PreparedStatement ps = conn.prepareStatement("""
                    INSERT INTO players (uuid, name, playtime_minutes, last_daily_day)
                    VALUES (?, ?, ?, ?)
                    ON CONFLICT(uuid) DO UPDATE SET
                        name = excluded.name,
                        playtime_minutes = excluded.playtime_minutes,
                        last_daily_day = excluded.last_daily_day""")) {
                conn.setAutoCommit(false);
                for (PlayerData.Snapshot s : snapshots) {
                    ps.setString(1, s.uuid().toString());
                    ps.setString(2, s.name());
                    ps.setLong(3, s.playtimeMinutes());
                    if (s.lastDailyDay() == PlayerData.NEVER) ps.setNull(4, java.sql.Types.INTEGER);
                    else ps.setLong(4, s.lastDailyDay());
                    ps.addBatch();
                }
                ps.executeBatch();
                conn.commit();
            } catch (SQLException e) {
                log.severe("Failed to save player data: " + e.getMessage());
                try { conn.rollback(); } catch (SQLException ignored) { }
            } finally {
                try { conn.setAutoCommit(true); } catch (SQLException ignored) { }
            }
        });
    }

    /** Flushes queued writes, then closes the connection. */
    public void close() {
        executor.execute(() -> {
            try { if (conn != null) conn.close(); } catch (SQLException ignored) { }
        });
        executor.shutdown();
        try {
            executor.awaitTermination(15, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
