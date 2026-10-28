package com.carddemo.iam;

import com.carddemo.test.SampleData;
import com.carddemo.test.ScreenClient;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

/** Shared setup of the user maintenance screen tests: sample data and a signed-on ADMIN001 client. */
abstract class UserScreenTestSupport {

    @Autowired
    MockMvc mvc;
    @Autowired
    SampleData sample;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    PasswordEncoder encoder;

    ScreenClient admin;

    @BeforeEach
    void loadAndSignOn() {
        sample.load();
        admin = signedOn("ADMIN001");
    }

    ScreenClient signedOn(String userId) {
        ScreenClient c = new ScreenClient(mvc);
        c.signOn(userId);
        return c;
    }

    static List<String> userIds(JsonNode r) {
        List<String> ids = new ArrayList<>();
        r.at("/fields/rows").forEach(row -> ids.add(row.get("userId").asText()));
        return ids;
    }

    /** Sel codes for rows 1-10 with {@code code} on 1-based {@code row}. */
    static List<String> sel(Object... rowAndCode) {
        List<String> s = new ArrayList<>(List.of("", "", "", "", "", "", "", "", "", ""));
        for (int i = 0; i < rowAndCode.length; i += 2) {
            s.set((Integer) rowAndCode[i] - 1, (String) rowAndCode[i + 1]);
        }
        return s;
    }

    String column(String userId, String column) {
        return jdbc.queryForObject("SELECT " + column + " FROM app_user WHERE user_id = ?", String.class, userId);
    }

    boolean exists(String userId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM app_user WHERE user_id = ?", Integer.class, userId) > 0;
    }

    int audits(String action, String target) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE action = ? AND target = ?", Integer.class, action, target);
    }

    /** Replaces every user by T0000001-T0000025 (type A, password PASSWORD); sessions already signed on stay valid. */
    void twentyFiveUsers() {
        jdbc.update("DELETE FROM app_user");
        String hash = encoder.encode(ScreenClient.DEMO_PASSWORD);
        for (int i = 1; i <= 25; i++) {
            jdbc.update("INSERT INTO app_user (user_id, first_name, last_name, password_hash, user_type) VALUES (?, ?, ?, ?, 'A')",
                String.format("T%07d", i), "FIRST" + i, "LAST" + i, hash);
        }
    }

    static List<String> range(int from, int to) {
        List<String> ids = new ArrayList<>();
        for (int i = from; i <= to; i++) {
            ids.add(String.format("T%07d", i));
        }
        return ids;
    }

    static List<String> padded(List<String> ids) {
        List<String> out = new ArrayList<>(ids);
        while (out.size() < 10) {
            out.add("");
        }
        return out;
    }
}
