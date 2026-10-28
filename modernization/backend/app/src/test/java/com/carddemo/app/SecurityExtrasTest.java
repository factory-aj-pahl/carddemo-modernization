package com.carddemo.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.carddemo.test.Covers;
import com.carddemo.test.ScreenClient;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * D-SEC-EXTRAS on the assembled API with an injected clock: one generic sign-on failure answer, lockout
 * after 5 consecutive failures for 15 minutes, 15-minute idle session timeout, and one audit row per
 * sign-on and per user, account, and card change, with no secret values.
 */
class SecurityExtrasTest extends SecurityTestBase {

    static final String GENERIC = "Invalid User ID or Password. Try again ...";
    static final String USER = "USER0001";

    @BeforeEach
    void reset() {
        clock.set(T0);
        sample.load();
        jdbc.update("DELETE FROM audit_log");
    }

    MockHttpServletResponse signOn(ScreenClient c, String userId, String password) {
        return c.turn("COSGN00C", "ENTER", Map.of("userId", userId, "password", password));
    }

    static String body(MockHttpServletResponse r) {
        try {
            return r.getContentAsString(StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** The sign-on answer with the echoed user ID blanked, so answers for different IDs compare byte for byte. */
    static String answer(MockHttpServletResponse r, String userId) {
        return r.getStatus() + " " + body(r).replace("\"userId\":\"" + userId + "\"", "\"userId\":\"<id>\"");
    }

    @Test
    @Covers({"D-SEC-EXTRAS", "Q-COSGN00C-04", "Q-COSGN00C-08"})
    void unknown_user_and_wrong_password_get_the_identical_answer() {
        MockHttpServletResponse wrong = signOn(new ScreenClient(mvc), USER, "WRONGPWD");
        MockHttpServletResponse unknown = signOn(new ScreenClient(mvc), "NOSUCHUS", "WRONGPWD");
        MockHttpServletResponse unknownRightPassword = signOn(new ScreenClient(mvc), "NOSUCHUS", ScreenClient.DEMO_PASSWORD);
        assertThat(wrong.getStatus()).isEqualTo(200);
        assertThat(new ScreenClient(mvc).body(wrong).at("/message").asText()).isEqualTo(GENERIC);
        assertThat(answer(unknown, "NOSUCHUS")).isEqualTo(answer(wrong, USER));
        assertThat(answer(unknownRightPassword, "NOSUCHUS")).isEqualTo(answer(wrong, USER));
    }

    @Test
    @Covers({"D-SEC-EXTRAS", "Q-COSGN00C-05"})
    void five_failures_lock_the_id_for_15_minutes_even_for_the_right_password() {
        ScreenClient c = new ScreenClient(mvc);
        String reference = answer(signOn(new ScreenClient(mvc), "NOSUCHUS", "WRONGPWD"), "NOSUCHUS");
        for (int i = 1; i <= 5; i++) {
            clock.advance(Duration.ofSeconds(10));
            assertThat(answer(signOn(c, USER, "WRONG" + i), USER)).as("failure " + i).isEqualTo(reference);
        }
        LocalDateTime lockedAt = clock.now();

        clock.advance(Duration.ofSeconds(1));
        MockHttpServletResponse sixth = signOn(c, USER, ScreenClient.DEMO_PASSWORD);
        assertThat(answer(sixth, USER)).as("6th attempt, right password, while locked").isEqualTo(reference);

        clock.set(lockedAt.plus(Duration.ofMinutes(15)).minusSeconds(1));
        assertThat(answer(signOn(c, USER, ScreenClient.DEMO_PASSWORD), USER)).as("one second before the window ends")
            .isEqualTo(reference);

        clock.set(lockedAt.plus(Duration.ofMinutes(15)));
        JsonNode ok = c.body(signOn(c, USER, ScreenClient.DEMO_PASSWORD));
        assertThat(ok.at("/navigate").asText()).as("right password after the 15-minute window").isEqualTo("COMEN01C");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE action = 'SIGNON_LOCKOUT' AND target = ?",
            Integer.class, USER)).isEqualTo(1);
    }

    @Test
    @Covers({"D-SEC-EXTRAS", "Q-COSGN00C-05"})
    void only_consecutive_failures_count_toward_the_lockout() {
        ScreenClient c = new ScreenClient(mvc);
        for (int i = 0; i < 4; i++) {
            signOn(c, USER, "WRONG" + i);
        }
        assertThat(c.body(signOn(c, USER, ScreenClient.DEMO_PASSWORD)).at("/navigate").asText()).isEqualTo("COMEN01C");
        ScreenClient d = new ScreenClient(mvc);
        for (int i = 0; i < 4; i++) {
            signOn(d, USER, "WRONG" + i);
        }
        assertThat(d.body(signOn(d, USER, ScreenClient.DEMO_PASSWORD)).at("/navigate").asText())
            .as("4 failures after a success do not lock").isEqualTo("COMEN01C");
    }

    @Test
    @Covers({"D-SEC-EXTRAS", "Q-COSGN00C-05", "C-SESSION"})
    void a_session_idle_for_more_than_15_minutes_is_rejected() {
        ScreenClient c = new ScreenClient(mvc);
        assertThat(c.signOn(USER).at("/navigate").asText()).isEqualTo("COMEN01C");

        clock.advance(Duration.ofMinutes(15));
        assertThat(c.showRaw("COMEN01C").getStatus()).as("exactly 15 idle minutes").isEqualTo(200);

        clock.advance(Duration.ofMinutes(14));
        assertThat(c.showRaw("COMEN01C").getStatus()).as("activity restarts the idle time").isEqualTo(200);

        clock.advance(Duration.ofMinutes(15).plusSeconds(1));
        assertThat(c.showRaw("COMEN01C").getStatus()).as("idle past 15 minutes").isEqualTo(401);
        assertThat(c.showRaw("COACTVWC").getStatus()).as("the expired session stays ended").isEqualTo(401);
        assertThat(c.turn("COBIL00C", "ENTER", Map.of("accountId", "00000000001")).getStatus()).isEqualTo(401);
    }

    // ---- Audit log -----------------------------------------------------------------------------------------------

    long lastAuditId() {
        Long id = jdbc.queryForObject("SELECT MAX(audit_id) FROM audit_log", Long.class);
        return id == null ? 0 : id;
    }

    /** Runs the action and returns the single audit row it wrote, checked for actor, action, target, time, and secrets. */
    Map<String, Object> oneAuditRow(String action, String actor, String target, Runnable change, List<String> secrets) {
        clock.advance(Duration.ofMinutes(1));
        long before = lastAuditId();
        change.run();
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM audit_log WHERE audit_id > ? ORDER BY audit_id", before);
        assertThat(rows).as(action + " writes exactly one audit row").hasSize(1);
        Map<String, Object> row = rows.get(0);
        assertThat(row).containsEntry("action", action).containsEntry("actor", actor).containsEntry("target", target);
        assertThat(((Timestamp) row.get("recorded_at")).toLocalDateTime()).as(action + " time").isEqualTo(clock.now());
        String all = row.toString();
        assertThat(all).doesNotContain("$2a$", "$2b$", "$2y$");
        for (String s : secrets) {
            assertThat(all).as(action + " audit row must not hold a secret").doesNotContain(s);
        }
        assertThat(all).doesNotContainPattern("(?<!\\d)\\d{16}(?!\\d)").doesNotContainPattern("(?<!\\d)\\d{9}(?!\\d)")
            .doesNotContainPattern("\\d{3}-\\d{2}-\\d{4}");
        return row;
    }

    @Test
    @Covers({"D-SEC-EXTRAS", "C-AUDIT"})
    void each_signon_and_each_user_account_and_card_change_writes_one_audit_row_without_secrets() {
        String card = "0500024453765740";
        String ssn24 = jdbc.queryForObject("SELECT c.ssn FROM customer c JOIN card_xref x ON x.customer_id = c.customer_id "
            + "WHERE x.account_id = 24", String.class).strip();
        List<String> secrets = List.of(ScreenClient.DEMO_PASSWORD, "WRONGPWD", "Secret12", "Newpass9", card, ssn24);

        oneAuditRow("SIGNON_FAILURE", USER, USER, () -> signOn(new ScreenClient(mvc), USER, "WRONGPWD"), secrets);
        oneAuditRow("SIGNON_SUCCESS", USER, USER, () -> new ScreenClient(mvc).signOn(USER), secrets);

        ScreenClient admin = new ScreenClient(mvc);
        oneAuditRow("SIGNON_SUCCESS", "ADMIN001", "ADMIN001", () -> admin.signOn("ADMIN001"), secrets);

        oneAuditRow("USER_ADD", "ADMIN001", "AUDT0001", () -> {
            admin.show("COUSR01C");
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("firstName", "Audit");
            f.put("lastName", "Trail");
            f.put("userId", "AUDT0001");
            f.put("password", "Secret12");
            f.put("userType", "U");
            f.put("accounts", "00000000003");
            assertThat(admin.post("COUSR01C", "ENTER", f).at("/message").asText()).isEqualTo("User AUDT0001 has been added ...");
        }, secrets);

        oneAuditRow("USER_UPDATE", "ADMIN001", "AUDT0001", () -> {
            admin.show("COUSR02C");
            JsonNode shown = admin.post("COUSR02C", "ENTER", Map.of("userId", "AUDT0001"));
            Map<String, Object> f = new HashMap<>();
            shown.get("fields").fields().forEachRemaining(e -> f.put(e.getKey(), e.getValue().asText()));
            f.put("lastName", "Changed");
            f.put("password", "Newpass9");
            assertThat(admin.post("COUSR02C", "PF5", f).at("/message").asText()).isEqualTo("User AUDT0001 has been updated ...");
        }, secrets);

        oneAuditRow("USER_DELETE", "ADMIN001", "AUDT0001", () -> {
            admin.show("COUSR03C");
            admin.post("COUSR03C", "ENTER", Map.of("userId", "AUDT0001"));
            assertThat(admin.post("COUSR03C", "PF5", Map.of("userId", "AUDT0001")).at("/message").asText())
                .isEqualTo("User AUDT0001 has been deleted ...");
        }, secrets);

        oneAuditRow("ACCOUNT_UPDATE", "ADMIN001", "00000000024", () -> {
            admin.show("COACTUPC");
            JsonNode shown = admin.post("COACTUPC", "ENTER", Map.of("fields", Map.of("accountId", "00000000024")));
            Map<String, String> values = new LinkedHashMap<>();
            shown.at("/fields/values").fields().forEachRemaining(e -> values.put(e.getKey(), e.getValue().asText()));
            values.put("zip", "66044");
            admin.post("COACTUPC", "ENTER", Map.of("fields", values));
            assertThat(admin.post("COACTUPC", "PF5", Map.of("fields", values)).at("/fields/info").asText())
                .isEqualTo("Changes committed to database");
        }, secrets);

        oneAuditRow("CARD_UPDATE", "ADMIN001", "************5740", () -> {
            admin.show("COCRDUPC");
            JsonNode shown = admin.post("COCRDUPC", "ENTER", Map.of("accountId", "00000000050", "cardNum", card));
            Map<String, Object> f = new HashMap<>();
            for (String name : List.of("accountId", "cardNum", "name", "status", "expMonth", "expYear")) {
                f.put(name, shown.at("/fields/" + name).asText());
            }
            f.put("status", "N");
            JsonNode validated = admin.post("COCRDUPC", "ENTER", f);
            for (String name : List.of("accountId", "cardNum", "name", "status", "expMonth", "expYear")) {
                f.put(name, validated.at("/fields/" + name).asText());
            }
            assertThat(admin.post("COCRDUPC", "PF5", f).at("/fields/state").asText()).isEqualTo("C");
        }, secrets);

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_log", Integer.class)).isEqualTo(8);
    }
}
