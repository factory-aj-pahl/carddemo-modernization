package com.carddemo.authorization;

import com.carddemo.test.SampleData;
import com.carddemo.test.ScreenClient;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/** Shared setup of the COPAUS0C/1C screen tests: base sample plus the IMS sample, clock at 2024-03-05 10:00, ADMIN001. */
abstract class AuthScreenTestSupport {

    static final String ACCT7 = "00000000007";
    static final long ACCOUNT_7 = 7;
    /** Account 7's newest authorization (TS-COPAUS1C-01) and the next older one. */
    static final String NEWEST_TXN = "40c56bdf89a74c1";
    static final String SECOND_TXN = "d347996bc29447e";

    @TestConfiguration(proxyBeanMethods = false)
    static class Clocks {
        @Bean
        @Primary
        AuthFixtures.MutableClock mutableClock() {
            return new AuthFixtures.MutableClock();
        }
    }

    @Autowired
    MockMvc mvc;
    @Autowired
    SampleData sample;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    Clock clock;

    ScreenClient admin;

    @BeforeEach
    void loadAndSignOn() {
        AuthFixtures.load(sample, jdbc);
        setToday(LocalDateTime.of(2024, 3, 5, 10, 0));
        admin = signedOn("ADMIN001");
    }

    void setToday(LocalDateTime t) {
        ((AuthFixtures.MutableClock) clock).set(t);
    }

    ScreenClient signedOn(String userId) {
        ScreenClient c = new ScreenClient(mvc);
        c.signOn(userId);
        return c;
    }

    /** One COPAUS0C turn with the account field and the select codes of rows 1-5 ({@code row, code} pairs). */
    static JsonNode list(ScreenClient c, String key, String acct, Object... rowAndCode) {
        List<String> sel = new ArrayList<>(List.of("", "", "", "", ""));
        for (int i = 0; i < rowAndCode.length; i += 2) {
            sel.set((Integer) rowAndCode[i] - 1, (String) rowAndCode[i + 1]);
        }
        Map<String, Object> f = new HashMap<>();
        f.put("accountId", acct);
        f.put("sel", sel);
        return c.post("COPAUS0C", key, f);
    }

    JsonNode list(String key, String acct, Object... rowAndCode) {
        return list(admin, key, acct, rowAndCode);
    }

    static JsonNode detail(ScreenClient c, String key) {
        return c.post("COPAUS1C", key, Map.of());
    }

    /** The transaction ids of the shown rows (empty rows give ""). */
    static List<String> txns(JsonNode r) {
        List<String> out = new ArrayList<>();
        r.at("/fields/rows").forEach(row -> out.add(row.get("transactionId").asText()));
        return out;
    }

    /** The account's transaction ids newest first. */
    List<String> allTxns(long account) {
        return jdbc.queryForList("SELECT transaction_id FROM pending_auth_detail WHERE account_id = ? ORDER BY auth_ts DESC, "
            + "auth_id DESC", String.class, account);
    }

    /** Rows {@code from}..{@code to} (1-based) of the account, padded with "" to five. */
    List<String> rows(long account, int from, int to) {
        List<String> all = allTxns(account);
        List<String> out = new ArrayList<>(all.subList(from - 1, Math.min(to, all.size())));
        while (out.size() < 5) {
            out.add("");
        }
        return out;
    }

    long authId(String transactionId) {
        return jdbc.queryForObject("SELECT auth_id FROM pending_auth_detail WHERE transaction_id = ?", Long.class, transactionId);
    }

    Map<String, Object> detailRow(String transactionId) {
        Map<String, Object> row = new HashMap<>(jdbc.queryForMap("SELECT * FROM pending_auth_detail WHERE transaction_id = ?",
            transactionId));
        row.remove("row_version");
        row.replaceAll((k, v) -> v instanceof byte[] b ? java.util.HexFormat.of().formatHex(b) : v);
        return row;
    }

    int fraudRows() {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM auth_fraud_report", Integer.class);
        return n == null ? 0 : n;
    }

    /** Shows account 7 and selects row {@code row} with S; returns the COPAUS1C first entry. */
    JsonNode openDetail(ScreenClient c, int row) {
        list(c, "ENTER", ACCT7);
        JsonNode nav = list(c, "ENTER", ACCT7, row, "S");
        if (!"COPAUS1C".equals(nav.at("/navigate").asText())) {
            throw new AssertionError("no navigation: " + nav);
        }
        return c.show("COPAUS1C");
    }
}
