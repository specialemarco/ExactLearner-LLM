package org.experiments.logger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.sql.SQLException;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.*;
import static org.hamcrest.core.IsNull.*;


public class CacheManagerTest {
    private final String testFile = "test.sqlite3";
    private CacheManager cacheManager;

    @BeforeEach
    public void setUp() {
        cacheManager = new CacheManager(testFile);
    }

    @AfterEach
    public void tearDown() {
        new File(testFile).delete();
    }

    @Test
    public void testCacheManager() throws SQLException {
        Cache cache = cacheManager.getCache("something", "something");
        String query = "Is it true?";
        Boolean awnser = cache.isStrictlyTrue(query);
        assertThat(awnser, nullValue());

        cache.storeQuery(query, "False.");
        awnser = cache.isStrictlyTrue(query);
        assertThat(awnser, is(false));

        cacheManager = new CacheManager(testFile);
        awnser = cache.isStrictlyTrue(query);
        assertThat(awnser, is(false));

        query = "Wazzza";
        cache.storeQuery(query, "I.");
        awnser = cache.isStrictlyTrue(query);
        assertThat(awnser, is(false));

        query = "It is true";
        cache.storeQuery(query, "\tTrue");
        awnser = cache.isStrictlyTrue(query);
        assertThat(awnser, is(true));
    }
}