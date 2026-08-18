package org.experiments.logger;

import org.utility.BaseDBHandler;

import java.io.File;
import java.sql.*;

public class CacheManager extends BaseDBHandler {
    private boolean migrator = false;

    public CacheManager() {
        this("cache.sqlite3", false);
    }

    public CacheManager(String filePath) {
        this(filePath, false);
    }

    public CacheManager(boolean migrator) {
        this("cache.sqlite3", migrator);
    }

    public CacheManager(String filePath, boolean migrator) {
        super(filePath);
        this.migrator = migrator;
    }

    @Override
    protected File[] getUpdateFiles() {
        try {
            File[] files = new File("src/main/java/org/experiments/logger/updates").listFiles();
            if (files == null) {
                throw new RuntimeException("Invalid update folder");
            }
            return files;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public Cache getCache(String model, String system) {
        try {
            int model_id = getOrCreateId("model", model);
            int system_id = getOrCreateId("system", system);
            return new Cache(connection, model_id, system_id);
        } catch (Exception e) {
            System.out.println("Could not get cache: " + e.getMessage());
            System.exit(1);
        }
        return null;
    }

    public Cache getCache(String model, String ontology, String task, String system) {
        try {
            int model_id = getOrCreateId("model", model);
            int system_id = getOrCreateId("system", system);
            if (migrator) {
                return new CacheMigrator(connection, model_id, system_id,
                        model, task, system);
            }
            return new Cache(connection, model_id, system_id);
        } catch (Exception e) {
            System.out.println("Could not get cache: " + e.getMessage());
            System.exit(1);
        }
        return null;
    }

    // Same defect Cache.resultString had, mirrored: the close() sat after an
    // early return, so the lookup leaked its statement whenever the row already
    // existed -- which is the common case. Only called twice per run, so this
    // is tidiness rather than a measurable win, but leaving one copy of the bug
    // next to the fixed one invites it back.
    private int getOrCreateId(String table, String text) throws SQLException {
        try (PreparedStatement query_ps = connection.prepareStatement(
                "SELECT ROWID FROM tbl_" + table + " WHERE " + table + "_text = ?")) {
            query_ps.setString(1, text);
            try (ResultSet rs = query_ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt(1);
                }
            }
        }

        try (PreparedStatement insert_ps = connection.prepareStatement(
                "INSERT INTO tbl_" + table + " VALUES (?)")) {
            insert_ps.setString(1, text);
            insert_ps.executeUpdate();

            try (ResultSet keys = insert_ps.getGeneratedKeys()) {
                if (keys.next()) {
                    return keys.getInt(1);
                }
            }
        }
        throw new SQLException("Failed find an id");
    }
}
