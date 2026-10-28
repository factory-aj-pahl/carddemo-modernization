package com.carddemo.iam;

import static org.assertj.core.api.Assertions.assertThat;

import com.carddemo.common.msg.CommonMessages;
import com.carddemo.test.Covers;
import com.carddemo.test.ScreenClient;
import com.carddemo.test.TestApplication;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(classes = TestApplication.class)
@AutoConfigureMockMvc
class UserAddTest extends UserScreenTestSupport {

    static Map<String, Object> form(String first, String last, String id, String pwd, String type) {
        Map<String, Object> m = new HashMap<>();
        m.put("firstName", first);
        m.put("lastName", last);
        m.put("userId", id);
        m.put("password", pwd);
        m.put("userType", type);
        return m;
    }

    @Test
    @Covers({"BR-COUSR01C-02", "TS-COUSR01C-01"})
    void first_entry_is_empty_with_focus_on_first_name() {
        JsonNode r = admin.show("COUSR01C");
        assertThat(r.at("/fields/firstName").asText()).isEmpty();
        assertThat(r.at("/fields/userId").asText()).isEmpty();
        assertThat(r.has("message")).isFalse();
        assertThat(r.at("/focus").asText()).isEqualTo("firstName");
    }

    @Test
    @Covers({"VR-COUSR01C-01", "VR-COUSR01C-02", "VR-COUSR01C-03", "VR-COUSR01C-04", "VR-COUSR01C-05", "TS-COUSR01C-02",
        "TS-COUSR01C-03", "TS-COUSR01C-04", "TS-COUSR01C-05", "TS-COUSR01C-06", "NAV-COUSR01C-01", "NAV-COUSR01C-05"})
    void mandatory_fields_are_checked_in_screen_order() {
        record Step(Map<String, Object> input, String message, String focus) {
        }
        List<Step> steps = List.of(
            new Step(form("", "", "", "", ""), "First Name can NOT be empty...", "firstName"),
            new Step(form("TEST", "", "", "", ""), "Last Name can NOT be empty...", "lastName"),
            new Step(form("TEST", "USER", "", "", ""), "User ID can NOT be empty...", "userId"),
            new Step(form("TEST", "USER", "TEST0001", "", ""), "Password can NOT be empty...", "password"),
            new Step(form("TEST", "USER", "TEST0001", "PASSWORD", " "), "User Type can NOT be empty...", "userType"));
        for (Step s : steps) {
            JsonNode r = admin.post("COUSR01C", "ENTER", s.input());
            assertThat(r.at("/message").asText()).isEqualTo(s.message());
            assertThat(r.at("/messageColor").asText()).isEqualTo("RED");
            assertThat(r.at("/focus").asText()).isEqualTo(s.focus());
            assertThat(r.at("/fields/firstName").asText()).isEqualTo(s.input().get("firstName"));
        }
        assertThat(exists("TEST0001")).isFalse();
    }

    @Test
    @Covers({"BR-COUSR01C-03", "BR-COUSR01C-04", "BR-COUSR01C-05", "TS-COUSR01C-07", "Q-COUSR01C-01", "C-AUDIT"})
    void a_valid_user_is_added_with_a_bcrypt_hash_and_the_screen_cleared() {
        JsonNode r = admin.post("COUSR01C", "ENTER", form("TEST", "USER", "TEST0001", "PASSWORD", "U"));
        assertThat(r.at("/message").asText()).isEqualTo("User TEST0001 has been added ...");
        assertThat(r.at("/messageColor").asText()).isEqualTo("GREEN");
        assertThat(r.at("/focus").asText()).isEqualTo("firstName");
        assertThat(r.at("/fields/firstName").asText()).isEmpty();
        assertThat(r.at("/fields/userId").asText()).isEmpty();
        assertThat(r.toString()).doesNotContain("PASSWORD");
        assertThat(column("TEST0001", "first_name")).isEqualTo("TEST");
        assertThat(column("TEST0001", "last_name")).isEqualTo("USER");
        assertThat(column("TEST0001", "user_type")).isEqualTo("U");
        String hash = column("TEST0001", "password_hash");
        assertThat(hash).startsWith("$2").isNotEqualTo("PASSWORD");
        assertThat(encoder.matches("PASSWORD", hash)).isTrue();
        assertThat(audits("USER_ADD", "TEST0001")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT actor FROM audit_log WHERE action = 'USER_ADD'", String.class)).isEqualTo("ADMIN001");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE fields COLLATE Latin1_General_CS_AS LIKE '%PASSWORD%' OR fields LIKE '%$2%'",
            Integer.class)).isZero();
        assertThat(new ScreenClient(mvc).signOn("TEST0001").at("/navigate").asText()).isEqualTo("COMEN01C");
    }

    @Test
    @Covers({"VR-COUSR01C-06", "ERR-COUSR01C-01", "TS-COUSR01C-08", "Q-COUSR01C-08"})
    void a_duplicate_user_id_is_rejected_and_the_record_unchanged() {
        String before = column("ADMIN001", "password_hash");
        JsonNode r = admin.post("COUSR01C", "ENTER", form("NEW", "NAME", "ADMIN001", "OTHERPWD", "U"));
        assertThat(r.at("/message").asText()).isEqualTo("User ID already exist...");
        assertThat(r.at("/focus").asText()).isEqualTo("userId");
        assertThat(column("ADMIN001", "user_type")).isEqualTo("A");
        assertThat(column("ADMIN001", "password_hash")).isEqualTo(before);
        assertThat(column("ADMIN001", "first_name")).isNotEqualTo("NEW");
    }

    @Test
    @Covers({"TS-COUSR01C-09", "Q-COUSR01C-03", "Q-COSGN00C-07"})
    void user_type_is_upper_cased_and_must_be_a_or_u() {
        JsonNode bad = admin.post("COUSR01C", "ENTER", form("TEST", "USER", "TEST0002", "PASSWORD", "Z"));
        assertThat(bad.at("/message").asText()).isEqualTo("User Type must be A or U...");
        assertThat(bad.at("/focus").asText()).isEqualTo("userType");
        assertThat(exists("TEST0002")).isFalse();
        JsonNode ok = admin.post("COUSR01C", "ENTER", form("TEST", "USER", "TEST0002", "PASSWORD", "a"));
        assertThat(ok.at("/message").asText()).isEqualTo("User TEST0002 has been added ...");
        assertThat(column("TEST0002", "user_type")).isEqualTo("A");
    }

    @Test
    @Covers({"TS-COUSR01C-10", "Q-COUSR01C-02", "Q-COSGN00C-02"})
    void a_lower_case_id_is_stored_upper_case_and_the_password_as_typed() {
        JsonNode r = admin.post("COUSR01C", "ENTER", form("Test", "User", "test0003", "abc12345", "U"));
        assertThat(r.at("/message").asText()).isEqualTo("User TEST0003 has been added ...");
        assertThat(exists("TEST0003")).isTrue();
        assertThat(column("TEST0003", "first_name")).isEqualTo("Test");
        assertThat(new ScreenClient(mvc).signOn("test0003", "abc12345").at("/navigate").asText()).isEqualTo("COMEN01C");
        assertThat(new ScreenClient(mvc).signOn("TEST0003", "ABC12345").at("/message").asText())
            .isEqualTo("Invalid User ID or Password. Try again ...");
    }

    @Test
    @Covers({"Q-COUSR01C-06"})
    void user_ids_must_be_letters_and_digits_only() {
        for (String id : new String[] {"AB CD", " ABC", "AB-1", "ÄBC"}) {
            JsonNode r = admin.post("COUSR01C", "ENTER", form("TEST", "USER", id, "PASSWORD", "U"));
            assertThat(r.at("/message").asText()).as(id).isEqualTo("User ID must be letters and digits only...");
            assertThat(r.at("/focus").asText()).isEqualTo("userId");
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM app_user", Integer.class)).isEqualTo(10);
    }

    @Test
    @Covers({"Q-COUSR01C-04"})
    void the_user_table_has_no_filler_column() {
        admin.post("COUSR01C", "ENTER", form("TEST", "USER", "TEST0004", "PASSWORD", "U"));
        List<String> cols = jdbc.queryForList(
            "SELECT LOWER(COLUMN_NAME) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME = 'app_user'", String.class);
        assertThat(cols).noneMatch(c -> c.contains("filler"));
    }

    @Test
    @Covers({"NAV-COUSR01C-03", "TS-COUSR01C-11"})
    void pf4_clears_all_fields_and_the_message() {
        JsonNode r = admin.post("COUSR01C", "PF4", form("TEST", "USER", "TEST0001", "PASSWORD", "U"));
        assertThat(r.at("/fields/firstName").asText()).isEmpty();
        assertThat(r.at("/fields/lastName").asText()).isEmpty();
        assertThat(r.at("/fields/userId").asText()).isEmpty();
        assertThat(r.at("/fields/userType").asText()).isEmpty();
        assertThat(r.has("message")).isFalse();
        assertThat(r.at("/focus").asText()).isEqualTo("firstName");
        assertThat(exists("TEST0001")).isFalse();
    }

    @Test
    @Covers({"NAV-COUSR01C-02", "TS-COUSR01C-12"})
    void pf3_returns_to_the_admin_menu_without_writing() {
        JsonNode r = admin.post("COUSR01C", "PF3", form("TEST", "USER", "TEST0001", "PASSWORD", "U"));
        assertThat(r.at("/navigate").asText()).isEqualTo("COADM01C");
        assertThat(exists("TEST0001")).isFalse();
    }

    @Test
    @Covers({"NAV-COUSR01C-04", "TS-COUSR01C-13", "Q-COUSR01C-05"})
    void pf12_returns_to_the_admin_menu_and_other_keys_are_invalid() {
        JsonNode exit = admin.post("COUSR01C", "PF12", form("TEST", "USER", "TEST0001", "PASSWORD", "U"));
        assertThat(exit.at("/navigate").asText()).isEqualTo("COADM01C");
        assertThat(exists("TEST0001")).isFalse();
        JsonNode r = admin.post("COUSR01C", "PF7", form("TEST", "USER", "TEST0001", "PASSWORD", "U"));
        assertThat(r.at("/message").asText()).isEqualTo(CommonMessages.INVALID_KEY);
        assertThat(r.at("/focus").asText()).isEqualTo("firstName");
        assertThat(r.at("/fields/lastName").asText()).isEqualTo("USER");
        assertThat(r.at("/fields").has("password")).isFalse();
    }

    @Test
    @Covers({"BR-COUSR01C-01", "Q-COUSR01C-07", "C-ROLE"})
    void add_user_requires_an_admin_session() {
        assertThat(new ScreenClient(mvc).showRaw("COUSR01C").getStatus()).isEqualTo(401);
        ScreenClient user = signedOn("USER0001");
        assertThat(user.turn("COUSR01C", "ENTER", form("TEST", "USER", "TEST0001", "PASSWORD", "U")).getStatus())
            .isEqualTo(403);
        assertThat(exists("TEST0001")).isFalse();
    }

    @Test
    @Covers({"ERR-COUSR01C-03"})
    void a_turn_with_no_fields_is_handled_as_blank_input() {
        assertThat(admin.post("COUSR01C", "ENTER", Map.of()).at("/message").asText()).isEqualTo("First Name can NOT be empty...");
    }

    @Test
    @Covers({"A-3", "D-USER-ACCT"})
    void account_links_are_saved_for_regular_users_and_scope_their_access() {
        Map<String, Object> f = form("LINK", "USER", "LINK0001", "PASSWORD", "U");
        f.put("accounts", "00000000005, 00000000001");
        JsonNode r = admin.post("COUSR01C", "ENTER", f);
        assertThat(r.at("/message").asText()).isEqualTo("User LINK0001 has been added ...");
        assertThat(jdbc.queryForList("SELECT account_id FROM user_account WHERE user_id = 'LINK0001' ORDER BY account_id",
            Long.class)).containsExactly(1L, 5L);
        assertThat(jdbc.queryForObject("SELECT fields FROM audit_log WHERE action = 'USER_ADD' AND target = 'LINK0001'",
            String.class)).contains("accounts");
    }

    @Test
    @Covers({"A-3"})
    void account_links_are_validated() {
        Map<String, Object> f = form("LINK", "USER", "LINK0002", "PASSWORD", "U");
        f.put("accounts", "1");
        assertThat(admin.post("COUSR01C", "ENTER", f).at("/message").asText()).isEqualTo("Linked accounts must be 11 digit numbers...");
        f.put("accounts", "99999999999");
        JsonNode missing = admin.post("COUSR01C", "ENTER", f);
        assertThat(missing.at("/message").asText()).isEqualTo("Account ID NOT found...");
        assertThat(missing.at("/focus").asText()).isEqualTo("accounts");
        f.put("accounts", "00000000001");
        f.put("userType", "A");
        assertThat(admin.post("COUSR01C", "ENTER", f).at("/message").asText())
            .isEqualTo("Only regular users can have linked accounts...");
        assertThat(exists("LINK0002")).isFalse();
    }
}
