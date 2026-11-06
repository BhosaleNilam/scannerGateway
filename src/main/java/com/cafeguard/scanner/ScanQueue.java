package com.cafeguard.scanner;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Local durable queue so scans are not lost when CafeGuard
 * is unreachable.
 */
public final class ScanQueue implements AutoCloseable {

    private static final Logger log =
            LoggerFactory.getLogger(ScanQueue.class);

    private final Connection connection;
    private final long dedupeWindowMs;

    public ScanQueue(Path dbPath, long dedupeWindowMs) throws Exception {
        this.dedupeWindowMs = Math.max(0L, dedupeWindowMs);
        Files.createDirectories(dbPath.getParent());
        String url = "jdbc:sqlite:" + dbPath;
        this.connection = DriverManager.getConnection(url);
        this.connection.setAutoCommit(true);
        try (Statement st = connection.createStatement()) {
            st.execute(
                    """
                    CREATE TABLE IF NOT EXISTS scans (
                      id INTEGER PRIMARY KEY AUTOINCREMENT,
                      payload TEXT NOT NULL,
                      status TEXT NOT NULL,
                      attempts INTEGER NOT NULL DEFAULT 0,
                      last_error TEXT,
                      created_at TEXT NOT NULL,
                      updated_at TEXT NOT NULL,
                      sent_at TEXT
                    )
                    """
            );
            st.execute(
                    """
                    CREATE INDEX IF NOT EXISTS idx_scans_status
                    ON scans(status, id)
                    """
            );
        }
        log.info("SQLite queue ready at {}", dbPath);
    }

    public synchronized Optional<Long> enqueue(String payload) {
        String text = payload == null ? "" : payload.trim();
        if (text.isEmpty()) {
            return Optional.empty();
        }
        if (isRecentDuplicate(text)) {
            log.info("Ignoring duplicate scan within window: {}", preview(text));
            return Optional.empty();
        }
        String now = Instant.now().toString();
        try (PreparedStatement ps = connection.prepareStatement(
                """
                INSERT INTO scans(payload, status, attempts, created_at, updated_at)
                VALUES (?, 'PENDING', 0, ?, ?)
                """,
                Statement.RETURN_GENERATED_KEYS
        )) {
            ps.setString(1, text);
            ps.setString(2, now);
            ps.setString(3, now);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) {
                    long id = keys.getLong(1);
                    log.info("Queued scan #{} ({})", id, preview(text));
                    return Optional.of(id);
                }
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to enqueue scan", ex);
        }
        return Optional.empty();
    }

    public synchronized List<QueuedScan> claimPending(
            int limit,
            int maxAttempts
    ) {
        List<QueuedScan> out = new ArrayList<>();
        try (
                PreparedStatement select = connection.prepareStatement(
                        """
                        SELECT id, payload, attempts
                        FROM scans
                        WHERE status IN ('PENDING', 'FAILED')
                          AND attempts < ?
                        ORDER BY id ASC
                        LIMIT ?
                        """
                )
        ) {
            select.setInt(1, Math.max(1, maxAttempts));
            select.setInt(2, Math.max(1, limit));
            try (ResultSet rs = select.executeQuery()) {
                while (rs.next()) {
                    out.add(
                            new QueuedScan(
                                    rs.getLong("id"),
                                    rs.getString("payload"),
                                    rs.getInt("attempts")
                            )
                    );
                }
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to claim scans", ex);
        }
        return out;
    }

    public synchronized void markSent(long id) {
        String now = Instant.now().toString();
        try (PreparedStatement ps = connection.prepareStatement(
                """
                UPDATE scans
                SET status = 'SENT',
                    updated_at = ?,
                    sent_at = ?,
                    last_error = NULL
                WHERE id = ?
                """
        )) {
            ps.setString(1, now);
            ps.setString(2, now);
            ps.setLong(3, id);
            ps.executeUpdate();
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to mark sent", ex);
        }
    }

    public synchronized void markFailed(long id, String error, int maxAttempts) {
        String now = Instant.now().toString();
        try (PreparedStatement ps = connection.prepareStatement(
                """
                UPDATE scans
                SET attempts = attempts + 1,
                    status = CASE
                      WHEN attempts + 1 >= ? THEN 'DEAD'
                      ELSE 'FAILED'
                    END,
                    last_error = ?,
                    updated_at = ?
                WHERE id = ?
                """
        )) {
            ps.setInt(1, maxAttempts);
            ps.setString(2, truncate(error, 500));
            ps.setString(3, now);
            ps.setLong(4, id);
            ps.executeUpdate();
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to mark failed", ex);
        }
    }

    private boolean isRecentDuplicate(String payload) {
        if (dedupeWindowMs <= 0) {
            return false;
        }
        Instant cutoff = Instant.now().minusMillis(dedupeWindowMs);
        try (PreparedStatement ps = connection.prepareStatement(
                """
                SELECT 1
                FROM scans
                WHERE payload = ?
                  AND created_at >= ?
                LIMIT 1
                """
        )) {
            ps.setString(1, payload);
            ps.setString(2, cutoff.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("Dedupe check failed", ex);
        }
    }

    private static String preview(String text) {
        if (text.length() <= 80) {
            return text;
        }
        return text.substring(0, 77) + "...";
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    @Override
    public void close() throws Exception {
        connection.close();
    }

    public record QueuedScan(long id, String payload, int attempts) {
    }
}
