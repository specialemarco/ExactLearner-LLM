package org.experiments.logger;

import java.sql.*;
import java.util.Locale;

public class Cache {

    private final Connection connection;
    private final Integer model_id;
    private final Integer system_id;

    // Prepared once and reused for the life of the Cache rather than rebuilt
    // per call. This path runs far more often than a run's model-query count:
    // resultString() is consulted for every task Environment.run() considers,
    // and again for every candidate the decomposition prefetcher dedups before
    // it builds a batch. Re-preparing meant re-parsing the same SQL on each of
    // those, and -- see below -- leaking the statement that did it.
    private PreparedStatement selectStatement;
    private PreparedStatement insertStatement;

    public Cache(Connection connection, Integer model_id, Integer system_id) {
        this.connection = connection;
        this.model_id = model_id;
        this.system_id = system_id;
    }

    public synchronized Boolean isStrictlyTrue(String query) {
        String res = resultString(query);
        if (res == null) {
            return null;
        }
        Boolean test = getIsStrictlyTrue(res);
        return (test != null) && test;
    }

    public synchronized String resultString(String query) {
        try {
            if (selectStatement == null) {
                selectStatement = connection.prepareStatement("""
                    SELECT result FROM tbl_cache
                        WHERE model_id = ?
                        AND system_id = ?
                        AND query = ?""");
            }

            selectStatement.setInt(1, model_id);
            selectStatement.setInt(2, system_id);
            selectStatement.setString(3, query);

            // The miss path used to return without closing either the result
            // set or the statement, and a miss is the common case here --
            // decompose()'s queries are novel by construction, so they never
            // hit. Every novel query therefore left a statement and its native
            // SQLite handle to be reclaimed whenever a Cleaner got round to it.
            try (ResultSet rs = selectStatement.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public synchronized void storeQuery(String query, String result) {
        try {
            if (insertStatement == null) {
                insertStatement = connection.prepareStatement("""
                        INSERT INTO tbl_cache(model_id, system_id, query, result)
                        VALUES (?, ?, ?, ?)""");
            }

            insertStatement.setInt(1, model_id);
            insertStatement.setInt(2, system_id);
            insertStatement.setString(3, query);
            insertStatement.setString(4, result);
            insertStatement.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
            throw new RuntimeException("Could not insert result");
        }
    }

    /**
     * Releases the two cached statements. Optional: the run has always relied
     * on process exit to clean up, and nothing calls this yet. It exists so a
     * caller that creates many Cache instances over one connection has a way
     * not to accumulate them.
     */
    public synchronized void close() {
        selectStatement = closeQuietly(selectStatement);
        insertStatement = closeQuietly(insertStatement);
    }

    private PreparedStatement closeQuietly(PreparedStatement ps) {
        if (ps != null) {
            try {
                ps.close();
            } catch (SQLException e) {
                // Closing is best-effort: a failure here must not take down a
                // run whose results are already written.
                System.out.println("Could not close cache statement: " + e);
            }
        }
        return null;
    }

    private Boolean getIsStrictlyTrue(String result) {
        if (result.toLowerCase(Locale.ROOT).contains("true")) return true;
        if (result.toLowerCase(Locale.ROOT).contains("false")) return false;
        return null;
    }
}
