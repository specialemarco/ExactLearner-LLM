package org.experiments.logger;

import java.sql.*;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class CacheManager {
    private Connection connection;

    public CacheManager() {
        this("cache.sqlite3");
    }

    public CacheManager(String filePath) {
        try {
            connection = DriverManager.getConnection("jdbc:sqlite:%s".formatted(filePath));
            setupSchema();
        } catch (Exception e) {
            System.out.println("Could not connect to database: " + e.getMessage());
            System.exit(1);
        }
    }

    // Schema updates in the order they apply, formerly updates/00N.sql. Each
    // name is recorded in tbl_updates, so an existing cache skips the ones it
    // has: never rename or reorder one, only append. A new cache runs all
    // three: 001 is the original schema, 002 drops ontology and task from the
    // key, 003 drops the unused bool_result.
    private static final List<Map.Entry<String, String>> UPDATES = List.of(
            Map.entry("001.sql", """
                    CREATE TABLE IF NOT EXISTS tbl_model (model_text TEXT NOT NULL UNIQUE);

                    CREATE TABLE IF NOT EXISTS tbl_ontology (ontology_text TEXT NOT NULL UNIQUE);

                    CREATE TABLE IF NOT EXISTS tbl_task (task_text TEXT NOT NULL UNIQUE);

                    CREATE TABLE IF NOT EXISTS tbl_system (system_text TEXT NOT NULL UNIQUE);

                    CREATE TABLE IF NOT EXISTS tbl_cache (
                        model_id INTEGER NOT NULL REFERENCES tbl_model(ROWID),
                        ontology_id INTEGER NOT NULL REFERENCES tbl_ontology(ROWID),
                        task_id INTEGER NOT NULL REFERENCES tbl_task(ROWID),
                        system_id INTEGER NOT NULL REFERENCES tbl_system(ROWID),
                        query TEXT NOT NULL,
                        result TEXT NOT NULL,
                        bool_result BOOLEAN
                    );

                    CREATE INDEX IF NOT EXISTS tbl_cache_index ON tbl_cache(model_id,ontology_id,task_id,system_id,query);
                    """),
            Map.entry("002.sql", """
                    CREATE TABLE IF NOT EXISTS tbl_new_cache (
                        model_id INTEGER NOT NULL REFERENCES tbl_model(ROWID),
                        system_id INTEGER NOT NULL REFERENCES tbl_system(ROWID),
                        query TEXT NOT NULL,
                        "result" TEXT NOT NULL,
                        bool_result BOOLEAN
                    );

                    INSERT INTO tbl_new_cache (model_id, system_id, query, "result", bool_result)
                        select model_id, system_id, query, "result", bool_result
                        from tbl_cache
                        group by model_id, system_id, query;

                    DROP INDEX tbl_cache_index;

                    DROP TABLE tbl_ontology;

                    DROP TABLE tbl_task;

                    DROP TABLE tbl_cache;

                    ALTER TABLE tbl_new_cache RENAME TO tbl_cache;

                    CREATE INDEX IF NOT EXISTS tbl_cache_index ON tbl_cache(model_id,system_id,query);
                    """),
            Map.entry("003.sql", """
                    ALTER TABLE tbl_cache DROP COLUMN bool_result;
                    """));

    private void setupSchema() throws SQLException {
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
        for (Map.Entry<String, String> update : UPDATES) {
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

    private int getOrCreateId(String table, String text) throws SQLException {
        Integer id = findId(table, text);
        if (id == null) {
            // OR IGNORE: another job on the same cache file can insert it between
            // the lookup and here, and the UNIQUE constraint would then kill this one.
            try (PreparedStatement insert_ps = connection.prepareStatement(
                    "INSERT OR IGNORE INTO tbl_" + table + " VALUES (?)")) {
                insert_ps.setString(1, text);
                insert_ps.executeUpdate();
            }
            id = findId(table, text);
        }
        if (id == null) {
            throw new SQLException("Failed find an id");
        }
        return id;
    }

    private Integer findId(String table, String text) throws SQLException {
        try (PreparedStatement query_ps = connection.prepareStatement(
                "SELECT ROWID FROM tbl_" + table + " WHERE " + table + "_text = ?")) {
            query_ps.setString(1, text);
            try (ResultSet rs = query_ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : null;
            }
        }
    }
}
