package com.carddemo.test;

import com.carddemo.migration.SampleDataLoader;
import java.io.IOException;
import java.io.UncheckedIOException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Resets the test database to the base sample data: the ASCII files, the 10 USRSEC users (BCrypt),
 * and the round-robin account links (D-SEED). The loaded users are hashed once per JVM.
 */
@Component
public class SampleData {

    private final JdbcTemplate jdbc;

    public SampleData(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Empty base tables. */
    public void clear() {
        new SampleDataLoader(jdbc).clear();
    }

    /** Clear, then load the full sample (accounts, cards, customers, xref, reference data, users, links). */
    public void load() {
        SampleDataLoader loader = new SampleDataLoader(jdbc, 4);
        loader.clear();
        try {
            loader.loadDirectory(RepoPaths.asciiData());
            loader.loadUsers(RepoPaths.usrsec());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
