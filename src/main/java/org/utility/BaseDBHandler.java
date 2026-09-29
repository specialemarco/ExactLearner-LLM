package org.utility;

import java.sql.*;
import java.util.*;

public abstract class BaseDBHandler {
    protected Connection connection;

    protected BaseDBHandler(String filePath) {
        try {
            connection = DriverManager.getConnection("jdbc:sqlite:%s".formatted(filePath));
            setupSchema();
        } catch (Exception e) {
            System.out.println("Could not connect to database: " + e.getMessage());
            System.exit(1);
        }
    }

    /**
     * Schema updates in the order they apply. The names are recorded in
     * tbl_updates, so an existing database skips the ones it already has:
     * never rename or reorder one, only append.
     */
    protected abstract List<Map.Entry<String, String>> getUpdates();

    protected void setupSchema() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA busy_timeout = 600000;");
            statement.execute("BEGIN IMMEDIATE;");
            boolean applied;
            try {
                statement.executeUpdate("CREATE TABLE IF NOT EXISTS tbl_updates (update_title TEXT NOT NULL UNIQUE);");
                applied = applyUpdates(statement);
                statement.execute("COMMIT;");
            } catch (SQLException | RuntimeException e) {
                statement.execute("ROLLBACK;");
                throw e;
            }
            // After the commit: SQLite refuses VACUUM inside a transaction.
            if (applied) {
                statement.execute("VACUUM;");
            }
        }
    }

    /** Applies the updates not yet in tbl_updates; true if any were. */
    private boolean applyUpdates(Statement statement) throws SQLException {
        Set<String> updated = new HashSet<>();
        try (ResultSet rs = statement.executeQuery("SELECT update_title FROM tbl_updates;")) {
            while (rs.next()) {
                updated.add(rs.getString(1));
            }
        }
        boolean applied = false;
        for (Map.Entry<String, String> update : getUpdates()) {
            if (updated.contains(update.getKey())) {
                continue;
            }
            for (String sql : update.getValue().split(";")) {
                if (!sql.isBlank()) {
                    statement.executeUpdate(sql.strip() + ";");
                }
            }
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO tbl_updates (update_title) VALUES (?);")) {
                insert.setString(1, update.getKey());
                insert.executeUpdate();
            }
            applied = true;
        }
        return applied;
    }
}
