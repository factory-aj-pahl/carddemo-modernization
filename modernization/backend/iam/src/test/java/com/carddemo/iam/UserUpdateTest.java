package com.carddemo.iam;

import static org.assertj.core.api.Assertions.assertThat;

import com.carddemo.common.msg.CommonMessages;
import com.carddemo.test.Covers;
import com.carddemo.test.ScreenClient;
import com.carddemo.test.TestApplication;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(classes = TestApplication.class)
@AutoConfigureMockMvc
class UserUpdateTest extends UserScreenTestSupport {

    /** Fetches {@code userId} on COUSR02C and returns the screen fields as a mutable form. */
    Map<String, Object> fetched(ScreenClient c, String userId) {
        c.show("COUSR02C");
        JsonNode r = c.post("COUSR02C", "ENTER", Map.of("userId", userId));
        assertThat(r.at("/message").asText()).isEqualTo("Press PF5 key to save your updates ...");
        Map<String, Object> f = new HashMap<>();
        r.get("fields").fields().forEachRemaining(e -> f.put(e.getKey(), e.getValue().asText()));
        return f;
    }

    void openFromList(ScreenClient c, int row) {
        c.show("COUSR00C");
        c.post("COUSR00C", "ENTER", Map.of("sel", sel(row, "U")));
    }

    @Test
    @Covers({"BR-COUSR02C-02", "BR-COUSR02C-03", "TS-COUSR02C-01", "Q-COUSR02C-03"})
    void opened_from_the_list_the_user_is_fetched_without_the_password() {
        openFromList(admin, 6);
        JsonNode r = admin.show("COUSR02C");
        assertThat(r.at("/fields/userId").asText()).isEqualTo("USER0001");
        assertThat(r.at("/fields/firstName").asText()).isNotBlank();
        assertThat(r.at("/fields/lastName").asText()).isNotBlank();
        assertThat(r.at("/fields/userType").asText()).isEqualTo("U");
        assertThat(r.at("/fields").has("password")).isFalse();
        assertThat(r.toString()).doesNotContain("$2").doesNotContain("PASSWORD");
        assertThat(r.at("/message").asText()).isEqualTo("Press PF5 key to save your updates ...");
        assertThat(r.at("/messageColor").asText()).isEqualTo("NEUTRAL");
    }

    @Test
    @Covers({"BR-COUSR02C-02", "TS-COUSR02C-02"})
    void opened_from_the_admin_menu_the_screen_is_empty() {
        JsonNode r = admin.show("COUSR02C");
        assertThat(r.at("/fields/userId").asText()).isEmpty();
        assertThat(r.at("/fields/firstName").asText()).isEmpty();
        assertThat(r.has("message")).isFalse();
        assertThat(r.at("/focus").asText()).isEqualTo("userId");
    }

    @Test
    @Covers({"VR-COUSR02C-01", "TS-COUSR02C-03", "VR-COUSR02C-02"})
    void fetch_and_save_need_a_user_id() {
        admin.show("COUSR02C");
        JsonNode r = admin.post("COUSR02C", "ENTER", Map.of("userId", ""));
        assertThat(r.at("/message").asText()).isEqualTo("User ID can NOT be empty...");
        assertThat(r.at("/focus").asText()).isEqualTo("userId");
        JsonNode save = admin.post("COUSR02C", "PF5", Map.of("userId", " ", "firstName", "X", "lastName", "Y", "userType", "U"));
        assertThat(save.at("/message").asText()).isEqualTo("User ID can NOT be empty...");
    }

    @Test
    @Covers({"ERR-COUSR02C-01", "TS-COUSR02C-04"})
    void an_unknown_user_is_not_found_and_the_fields_blank() {
        admin.show("COUSR02C");
        JsonNode r = admin.post("COUSR02C", "ENTER", Map.of("userId", "NOSUCHUS", "firstName", "OLD"));
        assertThat(r.at("/message").asText()).isEqualTo("User ID NOT found...");
        assertThat(r.at("/fields/firstName").asText()).isEmpty();
        assertThat(r.at("/fields/userType").asText()).isEmpty();
        assertThat(r.at("/focus").asText()).isEqualTo("userId");
    }

    @Test
    @Covers({"BR-COUSR02C-04", "BR-COUSR02C-05", "TS-COUSR02C-05", "NAV-COUSR02C-04", "NAV-COUSR02C-07", "C-AUDIT",
        "Q-COUSR02C-07"})
    void pf5_saves_the_changed_field_only() {
        Map<String, Object> f = fetched(admin, "USER0002");
        String first = column("USER0002", "first_name");
        String hash = column("USER0002", "password_hash");
        f.put("lastName", "NEWNAME");
        JsonNode r = admin.post("COUSR02C", "PF5", f);
        assertThat(r.at("/message").asText()).isEqualTo("User USER0002 has been updated ...");
        assertThat(r.at("/messageColor").asText()).isEqualTo("GREEN");
        assertThat(r.at("/fields/lastName").asText()).isEqualTo("NEWNAME");
        assertThat(r.has("navigate")).isFalse();
        assertThat(column("USER0002", "last_name")).isEqualTo("NEWNAME");
        assertThat(column("USER0002", "first_name")).isEqualTo(first);
        assertThat(column("USER0002", "password_hash")).isEqualTo(hash);
        assertThat(column("USER0002", "user_type")).isEqualTo("U");
        assertThat(jdbc.queryForObject("SELECT fields FROM audit_log WHERE action = 'USER_UPDATE' AND target = 'USER0002'",
            String.class)).isEqualTo("lastName");
    }

    @Test
    @Covers({"VR-COUSR02C-07", "TS-COUSR02C-06", "Q-COUSR02C-08"})
    void saving_without_changes_asks_to_modify_and_a_case_change_is_a_change() {
        Map<String, Object> f = fetched(admin, "USER0002");
        JsonNode r = admin.post("COUSR02C", "PF5", f);
        assertThat(r.at("/message").asText()).isEqualTo("Please modify to update ...");
        assertThat(r.at("/messageColor").asText()).isEqualTo("RED");
        assertThat(audits("USER_UPDATE", "USER0002")).isZero();
        String last = (String) f.get("lastName");
        f.put("lastName", last.toLowerCase());
        assertThat(admin.post("COUSR02C", "PF5", f).at("/message").asText()).isEqualTo("User USER0002 has been updated ...");
        assertThat(column("USER0002", "last_name")).isEqualTo(last.toLowerCase());
    }

    @Test
    @Covers({"VR-COUSR02C-03", "VR-COUSR02C-04", "VR-COUSR02C-06", "TS-COUSR02C-07", "TS-COUSR02C-08"})
    void blank_names_or_type_are_rejected_on_save() {
        Map<String, Object> f = fetched(admin, "USER0003");
        String[][] cases = {{"firstName", "First Name can NOT be empty..."}, {"lastName", "Last Name can NOT be empty..."},
            {"userType", "User Type can NOT be empty..."}};
        for (String[] c : cases) {
            Map<String, Object> edit = new HashMap<>(f);
            edit.put(c[0], "");
            JsonNode r = admin.post("COUSR02C", "PF5", edit);
            assertThat(r.at("/message").asText()).isEqualTo(c[1]);
            assertThat(r.at("/focus").asText()).isEqualTo(c[0]);
        }
        assertThat(audits("USER_UPDATE", "USER0003")).isZero();
    }

    @Test
    @Covers({"VR-COUSR02C-05", "TS-COUSR02C-08", "Q-COUSR02C-03"})
    void an_empty_password_keeps_the_stored_one_and_a_typed_one_replaces_it() {
        Map<String, Object> f = fetched(admin, "USER0003");
        String hash = column("USER0003", "password_hash");
        f.put("password", "");
        f.put("firstName", "RENAMED");
        assertThat(admin.post("COUSR02C", "PF5", f).at("/message").asText()).isEqualTo("User USER0003 has been updated ...");
        assertThat(column("USER0003", "password_hash")).isEqualTo(hash);
        f.put("password", "PASSWORD");
        assertThat(admin.post("COUSR02C", "PF5", f).at("/message").asText()).isEqualTo("Please modify to update ...");
        f.put("password", "newPass1");
        JsonNode r = admin.post("COUSR02C", "PF5", f);
        assertThat(r.at("/message").asText()).isEqualTo("User USER0003 has been updated ...");
        assertThat(r.toString()).doesNotContain("newPass1");
        assertThat(encoder.matches("newPass1", column("USER0003", "password_hash"))).isTrue();
        assertThat(new ScreenClient(mvc).signOn("USER0003", "newPass1").at("/navigate").asText()).isEqualTo("COMEN01C");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE fields LIKE '%newPass1%'", Integer.class)).isZero();
    }

    @Test
    @Covers({"NAV-COUSR02C-02", "TS-COUSR02C-09", "Q-COUSR02C-01"})
    void pf3_with_changes_saves_and_returns_to_the_list() {
        openFromList(admin, 8);
        JsonNode shown = admin.show("COUSR02C");
        assertThat(shown.at("/fields/userId").asText()).isEqualTo("USER0003");
        Map<String, Object> f = new HashMap<>(Map.of("userId", "USER0003", "firstName", "CHANGED",
            "lastName", shown.at("/fields/lastName").asText(), "userType", "U"));
        JsonNode r = admin.post("COUSR02C", "PF3", f);
        assertThat(r.at("/navigate").asText()).isEqualTo("COUSR00C");
        assertThat(r.has("message")).isFalse();
        assertThat(column("USER0003", "first_name")).isEqualTo("CHANGED");
    }

    @Test
    @Covers({"NAV-COUSR02C-02", "TS-COUSR02C-10", "Q-COUSR02C-01"})
    void pf3_stays_on_the_screen_when_the_save_fails_and_exits_silently_without_changes() {
        Map<String, Object> f = fetched(admin, "USER0003");
        String first = column("USER0003", "first_name");
        Map<String, Object> blanked = new HashMap<>(f);
        blanked.put("firstName", "");
        JsonNode r = admin.post("COUSR02C", "PF3", blanked);
        assertThat(r.has("navigate")).isFalse();
        assertThat(r.at("/message").asText()).isEqualTo("First Name can NOT be empty...");
        assertThat(column("USER0003", "first_name")).isEqualTo(first);
        JsonNode exit = admin.post("COUSR02C", "PF3", f);
        assertThat(exit.at("/navigate").asText()).isEqualTo("COADM01C");
        assertThat(exit.has("message")).isFalse();
        assertThat(audits("USER_UPDATE", "USER0003")).isZero();
    }

    @Test
    @Covers({"NAV-COUSR02C-05", "TS-COUSR02C-11"})
    void pf12_cancels_to_the_admin_menu_even_from_the_list() {
        openFromList(admin, 7);
        admin.show("COUSR02C");
        JsonNode r = admin.post("COUSR02C", "PF12", Map.of("userId", "USER0002", "firstName", "EDITED", "lastName", "X",
            "userType", "U"));
        assertThat(r.at("/navigate").asText()).isEqualTo("COADM01C");
        assertThat(column("USER0002", "first_name")).isNotEqualTo("EDITED");
    }

    @Test
    @Covers({"TS-COUSR02C-12", "Q-COUSR02C-02"})
    void pf5_with_another_user_id_fetches_that_user_and_writes_nothing() {
        Map<String, Object> f = fetched(admin, "USER0001");
        String before = column("USER0002", "first_name");
        f.put("userId", "USER0002");
        f.put("firstName", "OVERWRITE");
        JsonNode r = admin.post("COUSR02C", "PF5", f);
        assertThat(r.at("/message").asText()).isEqualTo("Press PF5 key to save your updates ...");
        assertThat(r.at("/fields/userId").asText()).isEqualTo("USER0002");
        assertThat(r.at("/fields/firstName").asText()).isEqualTo(before);
        assertThat(column("USER0002", "first_name")).isEqualTo(before);
        assertThat(column("USER0001", "first_name")).isNotEqualTo("OVERWRITE");
    }

    @Test
    @Covers({"TS-COUSR02C-13", "ERR-COUSR02C-03", "Q-COUSR02C-06"})
    void pf5_for_an_unknown_user_shows_not_found_and_writes_nothing() {
        admin.show("COUSR02C");
        JsonNode r = admin.post("COUSR02C", "PF5", Map.of("userId", "NOSUCHUS", "firstName", "A", "lastName", "B",
            "password", "PASSWORD", "userType", "U"));
        assertThat(r.at("/message").asText()).isEqualTo("User ID NOT found...");
        assertThat(r.at("/focus").asText()).isEqualTo("userId");
        assertThat(exists("NOSUCHUS")).isFalse();
    }

    @Test
    @Covers({"ERR-COUSR02C-04", "Q-COUSR02C-05", "Q-COUSR02C-06"})
    void a_user_deleted_after_the_fetch_is_not_found_on_save() {
        Map<String, Object> f = fetched(admin, "USER0004");
        jdbc.update("DELETE FROM app_user WHERE user_id = 'USER0004'");
        f.put("firstName", "GONE");
        JsonNode r = admin.post("COUSR02C", "PF5", f);
        assertThat(r.at("/message").asText()).isEqualTo("User ID NOT found...");
        assertThat(r.at("/focus").asText()).isEqualTo("userId");
        assertThat(exists("USER0004")).isFalse();
    }

    @Test
    @Covers({"Q-COUSR02C-05"})
    void a_concurrent_change_is_detected_and_the_current_values_shown() {
        Map<String, Object> mine = fetched(admin, "USER0005");
        ScreenClient other = signedOn("ADMIN002");
        Map<String, Object> theirs = fetched(other, "USER0005");
        theirs.put("firstName", "THEIRS");
        assertThat(other.post("COUSR02C", "PF5", theirs).at("/message").asText()).isEqualTo("User USER0005 has been updated ...");
        mine.put("lastName", "MINE");
        JsonNode r = admin.post("COUSR02C", "PF5", mine);
        assertThat(r.at("/message").asText()).isEqualTo("Record changed by some one else. Please review");
        assertThat(r.at("/fields/firstName").asText()).isEqualTo("THEIRS");
        assertThat(r.at("/fields/lastName").asText()).isNotEqualTo("MINE");
        assertThat(column("USER0005", "last_name")).isNotEqualTo("MINE");
        Map<String, Object> retry = new HashMap<>(mine);
        retry.put("firstName", "THEIRS");
        assertThat(admin.post("COUSR02C", "PF5", retry).at("/message").asText()).isEqualTo("User USER0005 has been updated ...");
        assertThat(column("USER0005", "last_name")).isEqualTo("MINE");
    }

    @Test
    @Covers({"NAV-COUSR02C-03", "TS-COUSR02C-14"})
    void pf4_clears_the_screen_and_forgets_the_fetch() {
        Map<String, Object> f = fetched(admin, "USER0001");
        JsonNode r = admin.post("COUSR02C", "PF4", f);
        assertThat(r.at("/fields/userId").asText()).isEmpty();
        assertThat(r.at("/fields/firstName").asText()).isEmpty();
        assertThat(r.has("message")).isFalse();
        assertThat(r.at("/focus").asText()).isEqualTo("userId");
        f.put("firstName", "AFTERCLR");
        assertThat(admin.post("COUSR02C", "PF5", f).at("/message").asText()).isEqualTo("Press PF5 key to save your updates ...");
        assertThat(column("USER0001", "first_name")).isNotEqualTo("AFTERCLR");
    }

    @Test
    @Covers({"NAV-COUSR02C-06", "TS-COUSR02C-15"})
    void other_keys_are_invalid_and_keep_the_typed_data() {
        Map<String, Object> f = fetched(admin, "USER0001");
        f.put("firstName", "TYPED");
        JsonNode r = admin.post("COUSR02C", "PF7", f);
        assertThat(r.at("/message").asText()).isEqualTo(CommonMessages.INVALID_KEY);
        assertThat(r.at("/fields/firstName").asText()).isEqualTo("TYPED");
        assertThat(column("USER0001", "first_name")).isNotEqualTo("TYPED");
    }

    @Test
    @Covers({"NAV-COUSR02C-01", "Q-COUSR02C-10"})
    void enter_refetches_and_discards_edits() {
        Map<String, Object> f = fetched(admin, "USER0001");
        String first = (String) f.get("firstName");
        f.put("firstName", "DISCARD");
        JsonNode r = admin.post("COUSR02C", "ENTER", f);
        assertThat(r.at("/fields/firstName").asText()).isEqualTo(first);
        assertThat(r.at("/message").asText()).isEqualTo("Press PF5 key to save your updates ...");
    }

    @Test
    @Covers({"BR-COUSR02C-06", "TS-COUSR02C-16", "Q-COUSR02C-04"})
    void a_type_change_to_admin_routes_the_next_signon_to_the_admin_menu_and_ends_old_sessions() {
        ScreenClient user4 = signedOn("USER0004");
        assertThat(user4.showRaw("COMEN01C").getStatus()).isEqualTo(200);
        Map<String, Object> f = fetched(admin, "USER0004");
        f.put("userType", "a");
        f.put("accounts", "");
        JsonNode r = admin.post("COUSR02C", "PF5", f);
        assertThat(r.at("/message").asText()).isEqualTo("User USER0004 has been updated ...");
        assertThat(column("USER0004", "user_type")).isEqualTo("A");
        assertThat(user4.showRaw("COMEN01C").getStatus()).isEqualTo(401);
        assertThat(new ScreenClient(mvc).signOn("USER0004").at("/navigate").asText()).isEqualTo("COADM01C");
        assertThat(admin.showRaw("COADM01C").getStatus()).isEqualTo(200);
    }

    @Test
    @Covers({"Q-COUSR02C-04", "Q-COSGN00C-07"})
    void the_type_must_be_a_or_u_and_an_admin_cannot_change_their_own_type() {
        Map<String, Object> f = fetched(admin, "USER0001");
        f.put("userType", "z");
        JsonNode bad = admin.post("COUSR02C", "PF5", f);
        assertThat(bad.at("/message").asText()).isEqualTo("User Type must be A or U...");
        assertThat(bad.at("/focus").asText()).isEqualTo("userType");
        f.put("userType", "u");
        assertThat(admin.post("COUSR02C", "PF5", f).at("/message").asText()).isEqualTo("Please modify to update ...");
        Map<String, Object> self = fetched(admin, "ADMIN001");
        self.put("userType", "U");
        JsonNode own = admin.post("COUSR02C", "PF5", self);
        assertThat(own.at("/message").asText()).isEqualTo("You cannot change your own User Type...");
        assertThat(column("ADMIN001", "user_type")).isEqualTo("A");
    }

    @Test
    @Covers({"Q-COUSR02C-04"})
    void a_password_change_ends_the_other_users_sessions() {
        ScreenClient user5 = signedOn("USER0005");
        Map<String, Object> f = fetched(admin, "USER0005");
        f.put("password", "Changed1");
        assertThat(admin.post("COUSR02C", "PF5", f).at("/message").asText()).isEqualTo("User USER0005 has been updated ...");
        assertThat(user5.showRaw("COMEN01C").getStatus()).isEqualTo(401);
        Map<String, Object> self = fetched(admin, "ADMIN001");
        self.put("password", "Mine0001");
        assertThat(admin.post("COUSR02C", "PF5", self).at("/message").asText()).isEqualTo("User ADMIN001 has been updated ...");
        assertThat(admin.showRaw("COADM01C").getStatus()).isEqualTo(200);
    }

    @Test
    @Covers({"BR-COUSR02C-01", "Q-COUSR02C-09", "C-ROLE"})
    void update_user_requires_an_admin_session() {
        assertThat(new ScreenClient(mvc).showRaw("COUSR02C").getStatus()).isEqualTo(401);
        ScreenClient user = signedOn("USER0001");
        assertThat(user.showRaw("COUSR02C").getStatus()).isEqualTo(403);
        assertThat(user.turn("COUSR02C", "ENTER", Map.of("userId", "ADMIN001")).getStatus()).isEqualTo(403);
    }

    @Test
    @Covers({"ERR-COUSR02C-06"})
    void a_turn_with_no_fields_is_handled_as_blank_input() {
        admin.show("COUSR02C");
        assertThat(admin.post("COUSR02C", "ENTER", Map.of()).at("/message").asText()).isEqualTo("User ID can NOT be empty...");
    }

    @Test
    @Covers({"A-3", "D-USER-ACCT"})
    void account_links_are_shown_and_updated() {
        Map<String, Object> f = fetched(admin, "USER0001");
        String links = (String) f.get("accounts");
        assertThat(links).matches("\\d{11}(, \\d{11})*");
        long linked = jdbc.queryForObject("SELECT COUNT(*) FROM user_account WHERE user_id = 'USER0001'", Long.class);
        assertThat(links.split(", ")).hasSize((int) linked);
        f.put("accounts", "00000000002");
        ScreenClient user1 = signedOn("USER0001");
        JsonNode r = admin.post("COUSR02C", "PF5", f);
        assertThat(r.at("/message").asText()).isEqualTo("User USER0001 has been updated ...");
        assertThat(jdbc.queryForList("SELECT account_id FROM user_account WHERE user_id = 'USER0001'", Long.class))
            .containsExactly(2L);
        assertThat(user1.showRaw("COMEN01C").getStatus()).isEqualTo(200);
        f.put("accounts", "2");
        assertThat(admin.post("COUSR02C", "PF5", f).at("/message").asText()).isEqualTo("Linked accounts must be 11 digit numbers...");
        f.put("accounts", "00000000002 99999999999");
        assertThat(admin.post("COUSR02C", "PF5", f).at("/message").asText()).isEqualTo("Account ID NOT found...");
        f.put("accounts", "00000000002");
        f.put("userType", "A");
        assertThat(admin.post("COUSR02C", "PF5", f).at("/message").asText())
            .isEqualTo("Only regular users can have linked accounts...");
        assertThat(column("USER0001", "user_type")).isEqualTo("U");
    }
}
