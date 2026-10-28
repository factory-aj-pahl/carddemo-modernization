package com.carddemo.account;

import com.carddemo.test.SampleData;
import com.carddemo.test.ScreenClient;
import com.carddemo.test.TestApplication;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/** Base sample data and a signed-off screen client for the account screens. */
@SpringBootTest(classes = TestApplication.class)
@AutoConfigureMockMvc
abstract class AccountScreenTestBase {

    static final String ADMIN = "ADMIN001";

    @Autowired
    MockMvc mvc;
    @Autowired
    SampleData sample;
    @Autowired
    JdbcTemplate jdbc;

    ScreenClient client;

    @BeforeEach
    void loadSample() {
        sample.load();
        client = new ScreenClient(mvc);
    }

    /** The first user linked to the account (D-USER-ACCT round-robin links). */
    String ownerOf(long account) {
        return jdbc.queryForObject("SELECT MIN(user_id) FROM user_account WHERE account_id = ?", String.class, account);
    }

    List<Long> linkedAccounts(String user) {
        return jdbc.queryForList("SELECT account_id FROM user_account WHERE user_id = ? ORDER BY account_id", Long.class, user);
    }

    static Map<String, String> values(JsonNode r) {
        Map<String, String> m = new LinkedHashMap<>();
        r.at("/fields/values").fields().forEachRemaining(e -> m.put(e.getKey(), e.getValue().asText()));
        return m;
    }

    static List<String> list(JsonNode r, String name) {
        List<String> out = new java.util.ArrayList<>();
        r.at("/fields/" + name).forEach(n -> out.add(n.asText()));
        return out;
    }
}
