package com.carddemo.iam;

import static org.assertj.core.api.Assertions.assertThat;

import com.carddemo.common.msg.CommonMessages;
import com.carddemo.test.Covers;
import com.carddemo.test.SampleData;
import com.carddemo.test.ScreenClient;
import com.carddemo.test.TestApplication;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(classes = TestApplication.class)
@AutoConfigureMockMvc
class SignonAndMenusTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    SampleData sample;
    @Autowired
    JdbcTemplate jdbc;

    ScreenClient client;

    @BeforeEach
    void load() {
        sample.load();
        client = new ScreenClient(mvc);
    }

    // ---- COSGN00C -------------------------------------------------------------------------------

    @Test
    @Covers({"BR-COSGN00C-01", "TS-COSGN00C-01"})
    void first_entry_shows_empty_signon_with_focus_on_user_id() {
        JsonNode r = client.show("COSGN00C");
        assertThat(r.at("/fields/userId").asText()).isEmpty();
        assertThat(r.has("message")).isFalse();
        assertThat(r.at("/focus").asText()).isEqualTo("userId");
    }

    @Test
    @Covers({"VR-COSGN00C-01", "TS-COSGN00C-02"})
    void blank_user_id_is_rejected() {
        JsonNode r = client.post("COSGN00C", "ENTER", Map.of("userId", "", "password", "PASSWORD"));
        assertThat(r.at("/message").asText()).isEqualTo("Please enter User ID ...");
        assertThat(r.at("/focus").asText()).isEqualTo("userId");
    }

    @Test
    @Covers({"VR-COSGN00C-02", "TS-COSGN00C-03"})
    void blank_password_is_rejected_and_user_id_kept() {
        JsonNode r = client.post("COSGN00C", "ENTER", Map.of("userId", "ADMIN001", "password", ""));
        assertThat(r.at("/message").asText()).isEqualTo("Please enter Password ...");
        assertThat(r.at("/fields/userId").asText()).isEqualTo("ADMIN001");
        assertThat(r.at("/focus").asText()).isEqualTo("password");
    }

    @Test
    @Covers({"BR-COSGN00C-06", "NAV-COSGN00C-04", "TS-COSGN00C-04", "TS-COSGN00C-05", "Q-COSGN00C-07"})
    void admin_goes_to_admin_menu_and_user_to_main_menu() {
        assertThat(client.signOn("ADMIN001").at("/navigate").asText()).isEqualTo("COADM01C");
        assertThat(new ScreenClient(mvc).signOn("USER0001").at("/navigate").asText()).isEqualTo("COMEN01C");
    }

    @Test
    @Covers({"Q-COSGN00C-02"})
    void user_id_is_case_insensitive_and_password_case_sensitive() {
        assertThat(client.signOn("user0001", "PASSWORD").at("/navigate").asText()).isEqualTo("COMEN01C");
        JsonNode bad = new ScreenClient(mvc).signOn("USER0001", "password");
        assertThat(bad.at("/message").asText()).isEqualTo("Invalid User ID or Password. Try again ...");
    }

    @Test
    @Covers({"Q-COSGN00C-04", "Q-COSGN00C-08", "TS-COSGN00C-06", "TS-COSGN00C-07"})
    void wrong_password_and_unknown_user_get_the_same_generic_answer() {
        JsonNode wrong = client.signOn("USER0001", "WRONGPWD");
        JsonNode unknown = new ScreenClient(mvc).signOn("NOSUCHUS", "PASSWORD");
        for (JsonNode r : new JsonNode[] {wrong, unknown}) {
            assertThat(r.at("/message").asText()).isEqualTo("Invalid User ID or Password. Try again ...");
            assertThat(r.at("/messageColor").asText()).isEqualTo("RED");
            assertThat(r.at("/focus").asText()).isEqualTo("password");
            assertThat(r.at("/fields").has("password")).isFalse();
            assertThat(r.has("navigate")).isFalse();
        }
        assertThat(wrong.at("/fields/userId").asText()).isEqualTo("USER0001");
    }

    @Test
    @Covers({"Q-COSGN00C-05", "C-AUDIT"})
    void five_failures_lock_the_id_even_for_the_correct_password() {
        for (int i = 0; i < 5; i++) {
            client.signOn("USER0001", "WRONG" + i);
        }
        JsonNode locked = client.signOn("USER0001", "PASSWORD");
        assertThat(locked.at("/message").asText()).isEqualTo("Invalid User ID or Password. Try again ...");
        assertThat(locked.has("navigate")).isFalse();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE action = 'SIGNON_LOCKOUT' AND actor = 'USER0001'",
            Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE fields LIKE '%PASSWORD%' OR fields LIKE '%WRONG%'",
            Integer.class)).isZero();
    }

    @Test
    @Covers({"NAV-COSGN00C-02", "TS-COSGN00C-09"})
    void pf3_on_signon_ends_with_thank_you() {
        JsonNode r = client.post("COSGN00C", "PF3", Map.of());
        assertThat(r.at("/message").asText()).isEqualTo(CommonMessages.THANK_YOU);
        assertThat(r.at("/navigate").asText()).isEqualTo("EXIT");
    }

    @Test
    @Covers({"NAV-COSGN00C-03", "TS-COSGN00C-10", "Q-COSGN00C-09"})
    void other_keys_on_signon_are_invalid() {
        for (String key : new String[] {"PF5", "CLEAR"}) {
            JsonNode r = client.post("COSGN00C", key, Map.of("userId", "USER0001"));
            assertThat(r.at("/message").asText()).isEqualTo(CommonMessages.INVALID_KEY);
            assertThat(r.at("/fields/userId").asText()).isEmpty();
        }
    }

    // ---- C-ROLE / C-SESSION ----------------------------------------------------------------------

    @Test
    @Covers({"C-ROLE", "Q-COSGN00C-10", "BR-COMEN01C-01", "BR-COADM01C-01", "TS-COMEN01C-10", "TS-COADM01C-08"})
    void requests_without_a_session_get_401() {
        assertThat(client.showRaw("COMEN01C").getStatus()).isEqualTo(401);
        assertThat(client.showRaw("COADM01C").getStatus()).isEqualTo(401);
        assertThat(client.turn("COBIL00C", "ENTER", Map.of()).getStatus()).isEqualTo(401);
    }

    @Test
    @Covers({"C-ROLE", "Q-COADM01C-01", "TS-COADM01C-09"})
    void a_user_session_cannot_reach_the_admin_menu() {
        client.signOn("USER0001");
        for (MockHttpServletResponse res : new MockHttpServletResponse[] {
            client.showRaw("COADM01C"), client.turn("COADM01C", "ENTER", Map.of("option", "1")), client.showRaw("COUSR00C")}) {
            assertThat(res.getStatus()).isEqualTo(403);
            JsonNode body = client.body(res);
            assertThat(body.at("/navigate").asText()).isEqualTo("COMEN01C");
            assertThat(body.at("/message").asText()).isEqualTo(CommonMessages.ADMIN_ONLY);
        }
    }

    @Test
    @Covers({"C-SESSION", "Q-COSGN00C-06"})
    void signon_issues_a_new_session_id() {
        client.show("COSGN00C");
        String before = client.session().getId();
        client.signOn("USER0001");
        assertThat(client.session().getId()).isNotEqualTo(before);
    }

    // ---- COMEN01C --------------------------------------------------------------------------------

    @Test
    @Covers({"BR-COMEN01C-02", "BR-COMEN01C-03", "TS-COMEN01C-01", "Q-COMEN01C-02"})
    void main_menu_lists_the_eleven_options() {
        client.signOn("USER0001");
        JsonNode r = client.show("COMEN01C");
        assertThat(r.at("/fields/options")).hasSize(11);
        assertThat(r.at("/fields/options/0").asText()).isEqualTo("01. Account View");
        assertThat(r.at("/fields/options/9").asText()).isEqualTo("10. Bill Payment");
        assertThat(r.at("/fields/options/10").asText()).isEqualTo("11. Pending Authorization View");
        assertThat(r.has("message")).isFalse();
    }

    @Test
    @Covers({"BR-COMEN01C-04", "BR-COMEN01C-08", "NAV-COMEN01C-04", "TS-COMEN01C-02", "TS-COMEN01C-03"})
    void main_menu_option_is_normalized_and_dispatched() {
        client.signOn("USER0001");
        for (String opt : new String[] {"3", " 3", "3 ", "03"}) {
            assertThat(client.post("COMEN01C", "ENTER", Map.of("option", opt)).at("/navigate").asText()).isEqualTo("COCRDLIC");
        }
        assertThat(client.post("COMEN01C", "ENTER", Map.of("option", "6")).at("/navigate").asText()).isEqualTo("COTRN00C");
        assertThat(client.post("COMEN01C", "ENTER", Map.of("option", "10")).at("/navigate").asText()).isEqualTo("COBIL00C");
    }

    @Test
    @Covers({"VR-COMEN01C-01", "TS-COMEN01C-04", "TS-COMEN01C-05", "Q-COMEN01C-03"})
    void main_menu_rejects_invalid_options() {
        client.signOn("USER0001");
        for (String opt : new String[] {"12", "", "0", "AB"}) {
            JsonNode r = client.post("COMEN01C", "ENTER", Map.of("option", opt));
            assertThat(r.at("/message").asText()).isEqualTo("Please enter a valid option number...");
            assertThat(r.has("navigate")).isFalse();
        }
    }

    @Test
    @Covers({"NAV-COMEN01C-02", "TS-COMEN01C-08"})
    void main_menu_pf3_signs_off() {
        client.signOn("USER0001");
        assertThat(client.post("COMEN01C", "PF3", Map.of()).at("/navigate").asText()).isEqualTo("COSGN00C");
        assertThat(client.showRaw("COMEN01C").getStatus()).isEqualTo(401);
    }

    @Test
    @Covers({"NAV-COMEN01C-03", "TS-COMEN01C-09"})
    void main_menu_other_keys_are_invalid() {
        client.signOn("USER0001");
        JsonNode r = client.post("COMEN01C", "PF7", Map.of("option", "1"));
        assertThat(r.at("/message").asText()).isEqualTo(CommonMessages.INVALID_KEY);
        assertThat(r.has("navigate")).isFalse();
    }

    // ---- COADM01C --------------------------------------------------------------------------------

    @Test
    @Covers({"BR-COADM01C-02", "BR-COADM01C-03", "TS-COADM01C-01", "Q-COADM01C-02"})
    void admin_menu_lists_the_six_options() {
        client.signOn("ADMIN001");
        JsonNode r = client.show("COADM01C");
        assertThat(r.at("/fields/options")).hasSize(6);
        assertThat(r.at("/fields/options/0").asText()).isEqualTo("01. User List (Security)");
        assertThat(r.at("/fields/options/5").asText()).isEqualTo("06. Transaction Type Maintenance (Db2)");
        assertThat(r.has("message")).isFalse();
    }

    @Test
    @Covers({"BR-COADM01C-04", "BR-COADM01C-05", "NAV-COADM01C-04", "TS-COADM01C-02", "TS-COADM01C-03"})
    void admin_menu_dispatches_options() {
        client.signOn("ADMIN001");
        String[] programs = {"COUSR00C", "COUSR01C", "COUSR02C", "COUSR03C", "COTRTLIC", "COTRTUPC"};
        for (int i = 0; i < programs.length; i++) {
            assertThat(client.post("COADM01C", "ENTER", Map.of("option", " " + (i + 1))).at("/navigate").asText())
                .isEqualTo(programs[i]);
        }
    }

    @Test
    @Covers({"VR-COADM01C-01", "TS-COADM01C-05"})
    void admin_menu_rejects_invalid_options() {
        client.signOn("ADMIN001");
        for (String opt : new String[] {"7", "0", ""}) {
            JsonNode r = client.post("COADM01C", "ENTER", Map.of("option", opt));
            assertThat(r.at("/message").asText()).isEqualTo("Please enter a valid option number...");
            assertThat(r.has("navigate")).isFalse();
        }
    }

    @Test
    @Covers({"NAV-COADM01C-02", "NAV-COADM01C-03", "TS-COADM01C-06", "TS-COADM01C-07"})
    void admin_menu_keys() {
        client.signOn("ADMIN001");
        assertThat(client.post("COADM01C", "PF2", Map.of()).at("/message").asText()).isEqualTo(CommonMessages.INVALID_KEY);
        assertThat(client.post("COADM01C", "PF3", Map.of()).at("/navigate").asText()).isEqualTo("COSGN00C");
        assertThat(client.showRaw("COADM01C").getStatus()).isEqualTo(401);
    }
}
