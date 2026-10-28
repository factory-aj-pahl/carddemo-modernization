package com.carddemo.transaction;

import com.carddemo.migration.Layouts;
import com.carddemo.migration.SampleDataLoader;
import com.carddemo.test.RepoPaths;
import com.carddemo.test.SampleData;
import com.carddemo.test.ScreenClient;
import com.carddemo.test.TestApplication;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/** Sample data with TRANSACT loaded from dailytran.txt (the spec's test assumption for COTRN00C-02C). */
@SpringBootTest(classes = TestApplication.class)
@AutoConfigureMockMvc
abstract class TransactionScreenTestBase {

    static final String ADMIN = "ADMIN001";

    @Autowired
    MockMvc mvc;
    @Autowired
    SampleData sample;
    @Autowired
    JdbcTemplate jdbc;

    ScreenClient client;

    @BeforeEach
    void loadSample() throws Exception {
        sample.load();
        new SampleDataLoader(jdbc).loadFile(Layouts.TRANSACT, RepoPaths.asciiData().resolve("dailytran.txt"));
        client = new ScreenClient(mvc);
    }

    String ownerOf(long account) {
        return jdbc.queryForObject("SELECT user_id FROM user_account WHERE account_id = ?", String.class, account);
    }

    /** Transaction IDs of a user's linked accounts' cards, ascending. */
    List<String> inScopeIds(String userId) {
        return jdbc.queryForList("""
            SELECT t.tran_id FROM card_transaction t WHERE t.card_num IN (SELECT x.card_num FROM card_xref x
              JOIN user_account ua ON ua.account_id = x.account_id WHERE ua.user_id = ?) ORDER BY t.tran_id
            """, String.class, userId);
    }

    List<String> allIds() {
        return jdbc.queryForList("SELECT tran_id FROM card_transaction ORDER BY tran_id", String.class);
    }

    int transactionCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM card_transaction", Integer.class);
    }
}
