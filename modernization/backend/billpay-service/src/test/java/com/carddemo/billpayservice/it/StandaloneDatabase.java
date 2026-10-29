package com.carddemo.billpayservice.it;

import com.carddemo.migration.SampleDataLoader;
import com.carddemo.test.RepoPaths;
import java.io.IOException;
import java.io.UncheckedIOException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * Direct database access for the integration tests: the database the started service uses
 * ({@code billpay.it.db}), reloaded once per test JVM with the base sample data and the USRSEC users.
 */
final class StandaloneDatabase {

    private static JdbcTemplate jdbc;

    private StandaloneDatabase() {
    }

    static synchronized JdbcTemplate loaded() {
        if (jdbc == null) {
            String db = System.getProperty("billpay.it.db");
            if (db == null || db.isBlank()) {
                throw new IllegalStateException("billpay.it.db is not set: run through 'mvn verify'");
            }
            DriverManagerDataSource ds = new DriverManagerDataSource(
                "jdbc:sqlserver://127.0.0.1:1433;databaseName=" + db + ";encrypt=true;trustServerCertificate=true",
                "carddemo_app", System.getenv("CARDDEMO_DB_PASSWORD"));
            JdbcTemplate t = new JdbcTemplate(ds);
            SampleDataLoader loader = new SampleDataLoader(t, 4);
            loader.clear();
            try {
                loader.loadDirectory(RepoPaths.asciiData());
                loader.loadUsers(RepoPaths.usrsec());
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            jdbc = t;
        }
        return jdbc;
    }

    /** An account linked to the user (or, with linked=false, linked to nobody but OTHER) that has a card cross-reference. */
    static String account(String userId, boolean linked, boolean positiveBalance) {
        String scope = linked
            ? "a.account_id IN (SELECT account_id FROM user_account WHERE user_id = ?)"
            : "a.account_id NOT IN (SELECT account_id FROM user_account WHERE user_id = ?)";
        Long id = loaded().queryForObject("SELECT MIN(a.account_id) FROM account a WHERE " + scope
            + (positiveBalance ? " AND a.current_balance > 0" : "")
            + " AND EXISTS (SELECT 1 FROM card_xref x WHERE x.account_id = a.account_id)", Long.class, userId);
        if (id == null) {
            throw new IllegalStateException("no " + (linked ? "linked" : "unlinked") + " account for " + userId);
        }
        return String.format("%011d", id);
    }
}
