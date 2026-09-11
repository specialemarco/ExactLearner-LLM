package org.utility;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
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

    protected abstract File[] getUpdateFiles();

    protected void setupSchema() throws SQLException, IOException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA busy_timeout = 600000;");
            statement.execute("BEGIN IMMEDIATE;");
            boolean applied;
            try {
                statement.executeUpdate("CREATE TABLE IF NOT EXISTS tbl_updates (update_title TEXT NOT NULL UNIQUE);");
                applied = applyUpdates(statement);
                statement.execute("COMMIT;");
            } catch (SQLException | IOException | RuntimeException e) {
                statement.execute("ROLLBACK;");
                throw e;
            }
            // After the commit: SQLite refuses VACUUM inside a transaction.
            if (applied) {
                statement.execute("VACUUM;");
            }
        }
    }

    /** Applies the update files not yet in tbl_updates; true if any were. */
    private boolean applyUpdates(Statement statement) throws SQLException, IOException {
        Set<String> updated = new HashSet<>();
        try (ResultSet rs = statement.executeQuery("SELECT update_title FROM tbl_updates;")) {
            while (rs.next()) {
                updated.add(rs.getString(1));
            }
        }
        boolean applied = false;
        for (File file : Arrays.stream(getUpdateFiles()).sorted().toList()) {
            if (updated.contains(file.getName())) {
                continue;
            }
            for (String update : Files.readString(file.toPath()).split(";")) {
                if (!update.isBlank()) {
                    statement.executeUpdate(update.strip() + ";");
                }
            }
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO tbl_updates (update_title) VALUES (?);")) {
                insert.setString(1, file.getName());
                insert.executeUpdate();
            }
            applied = true;
        }
        return applied;
    }
}
