package com.carddemo.iam;

import static org.assertj.core.api.Assertions.assertThat;

import com.carddemo.common.msg.CommonMessages;
import com.carddemo.test.Covers;
import com.carddemo.test.ScreenClient;
import com.carddemo.test.TestApplication;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(classes = TestApplication.class)
@AutoConfigureMockMvc
class UserDeleteTest extends UserScreenTestSupport {

    void openFromList(ScreenClient c, int row) {
        c.show("COUSR00C");
        assertThat(c.post("COUSR00C", "ENTER", Map.of("sel", sel(row, "D"))).at("/navigate").asText()).isEqualTo("COUSR03C");
    }

    @Test
    @Covers({"BR-COUSR03C-02", "BR-COUSR03C-03", "TS-COUSR03C-01"})
    void opened_from_the_list_the_user_is_shown_with_the_delete_prompt() {
        openFromList(admin, 10);
        JsonNode r = admin.show("COUSR03C");
        assertThat(r.at("/fields/userId").asText()).isEqualTo("USER0005");
        assertThat(r.at("/fields/firstName").asText()).isNotBlank();
        assertThat(r.at("/fields/lastName").asText()).isNotBlank();
        assertThat(r.at("/fields/userType").asText()).isEqualTo("U");
        assertThat(r.at("/fields").has("password")).isFalse();
        assertThat(r.at("/message").asText()).isEqualTo("Press PF5 key to delete this user ...");
        assertThat(r.at("/messageColor").asText()).isEqualTo("NEUTRAL");
    }

    @Test
    @Covers({"BR-COUSR03C-04", "BR-COUSR03C-05", "TS-COUSR03C-02", "NAV-COUSR03C-04", "Q-COUSR03C-07", "C-AUDIT"})
    void pf5_deletes_the_fetched_user_audits_it_and_ends_its_sessions() {
        ScreenClient user5 = signedOn("USER0005");
        openFromList(admin, 10);
        admin.show("COUSR03C");
        JsonNode r = admin.post("COUSR03C", "PF5", Map.of("userId", "USER0005"));
        assertThat(r.at("/message").asText()).isEqualTo("User USER0005 has been deleted ...");
        assertThat(r.at("/messageColor").asText()).isEqualTo("GREEN");
        assertThat(r.at("/fields/userId").asText()).isEmpty();
        assertThat(r.at("/fields/firstName").asText()).isEmpty();
        assertThat(r.at("/focus").asText()).isEqualTo("userId");
        assertThat(exists("USER0005")).isFalse();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_account WHERE user_id = 'USER0005'", Integer.class)).isZero();
        assertThat(audits("USER_DELETE", "USER0005")).isEqualTo(1);
        assertThat(user5.showRaw("COMEN01C").getStatus()).isEqualTo(401);
    }

    @Test
    @Covers({"VR-COUSR03C-01", "TS-COUSR03C-03", "VR-COUSR03C-02", "TS-COUSR03C-04"})
    void fetch_and_delete_need_a_user_id() {
        admin.show("COUSR03C");
        JsonNode enter = admin.post("COUSR03C", "ENTER", Map.of("userId", ""));
        assertThat(enter.at("/message").asText()).isEqualTo("User ID can NOT be empty...");
        assertThat(enter.at("/focus").asText()).isEqualTo("userId");
        JsonNode pf5 = admin.post("COUSR03C", "PF5", Map.of("userId", "  "));
        assertThat(pf5.at("/message").asText()).isEqualTo("User ID can NOT be empty...");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM app_user", Integer.class)).isEqualTo(10);
    }

    @Test
    @Covers({"ERR-COUSR03C-01", "TS-COUSR03C-05"})
    void an_unknown_user_is_not_found() {
        admin.show("COUSR03C");
        JsonNode r = admin.post("COUSR03C", "ENTER", Map.of("userId", "NOSUCHUS"));
        assertThat(r.at("/message").asText()).isEqualTo("User ID NOT found...");
        assertThat(r.at("/fields/firstName").asText()).isEmpty();
        assertThat(r.at("/focus").asText()).isEqualTo("userId");
    }

    @Test
    @Covers({"TS-COUSR03C-06", "ERR-COUSR03C-03", "Q-COUSR03C-03"})
    void pf5_for_an_unknown_user_is_a_fetch_that_finds_nothing() {
        admin.show("COUSR03C");
        JsonNode r = admin.post("COUSR03C", "PF5", Map.of("userId", "NOSUCHUS"));
        assertThat(r.at("/message").asText()).isEqualTo("User ID NOT found...");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM app_user", Integer.class)).isEqualTo(10);
    }

    @Test
    @Covers({"TS-COUSR03C-07", "Q-COUSR03C-01", "BR-COUSR03C-04"})
    void the_first_pf5_without_a_fetch_only_confirms_and_the_second_deletes() {
        admin.show("COUSR03C");
        JsonNode first = admin.post("COUSR03C", "PF5", Map.of("userId", "user0004"));
        assertThat(first.at("/message").asText()).isEqualTo("Press PF5 key to delete this user ...");
        assertThat(first.at("/fields/userId").asText()).isEqualTo("USER0004");
        assertThat(exists("USER0004")).isTrue();
        JsonNode second = admin.post("COUSR03C", "PF5", Map.of("userId", "USER0004"));
        assertThat(second.at("/message").asText()).isEqualTo("User USER0004 has been deleted ...");
        assertThat(exists("USER0004")).isFalse();
    }

    @Test
    @Covers({"Q-COUSR03C-01"})
    void pf5_after_changing_the_id_fetches_the_new_id_and_deletes_nothing() {
        admin.show("COUSR03C");
        admin.post("COUSR03C", "ENTER", Map.of("userId", "USER0001"));
        JsonNode r = admin.post("COUSR03C", "PF5", Map.of("userId", "USER0002"));
        assertThat(r.at("/message").asText()).isEqualTo("Press PF5 key to delete this user ...");
        assertThat(r.at("/fields/userId").asText()).isEqualTo("USER0002");
        assertThat(exists("USER0001")).isTrue();
        assertThat(exists("USER0002")).isTrue();
    }

    @Test
    @Covers({"NAV-COUSR03C-02", "TS-COUSR03C-08"})
    void pf3_returns_to_the_caller_without_deleting() {
        openFromList(admin, 8);
        assertThat(admin.show("COUSR03C").at("/fields/userId").asText()).isEqualTo("USER0003");
        assertThat(admin.post("COUSR03C", "PF3", Map.of("userId", "USER0003")).at("/navigate").asText()).isEqualTo("COUSR00C");
        assertThat(exists("USER0003")).isTrue();
        admin.show("COUSR03C");
        assertThat(admin.post("COUSR03C", "PF3", Map.of()).at("/navigate").asText()).isEqualTo("COADM01C");
    }

    @Test
    @Covers({"NAV-COUSR03C-05", "TS-COUSR03C-09", "Q-COUSR03C-04"})
    void pf12_goes_to_the_admin_menu() {
        openFromList(admin, 8);
        admin.show("COUSR03C");
        assertThat(admin.post("COUSR03C", "PF12", Map.of("userId", "USER0003")).at("/navigate").asText()).isEqualTo("COADM01C");
        assertThat(exists("USER0003")).isTrue();
    }

    @Test
    @Covers({"NAV-COUSR03C-03", "TS-COUSR03C-10"})
    void pf4_clears_the_screen() {
        admin.show("COUSR03C");
        admin.post("COUSR03C", "ENTER", Map.of("userId", "USER0001"));
        JsonNode r = admin.post("COUSR03C", "PF4", Map.of("userId", "USER0001"));
        assertThat(r.at("/fields/userId").asText()).isEmpty();
        assertThat(r.at("/fields/firstName").asText()).isEmpty();
        assertThat(r.has("message")).isFalse();
        assertThat(r.at("/focus").asText()).isEqualTo("userId");
    }

    @Test
    @Covers({"NAV-COUSR03C-06", "TS-COUSR03C-11", "NAV-COUSR03C-07", "NAV-COUSR03C-01", "Q-COUSR03C-06", "C-SHORTREAD"})
    void other_keys_are_invalid_and_keep_the_screen() {
        admin.show("COUSR03C");
        JsonNode fetched = admin.post("COUSR03C", "ENTER", Map.of("userId", "USER0001"));
        JsonNode r = admin.post("COUSR03C", "PF8", Map.of("userId", "USER0001"));
        assertThat(r.at("/message").asText()).isEqualTo(CommonMessages.INVALID_KEY);
        assertThat(r.at("/fields/firstName").asText()).isEqualTo(fetched.at("/fields/firstName").asText());
        assertThat(r.at("/focus").asText()).isEqualTo("userId");
        for (String shortRead : new String[] {"CLEAR", "PA1"}) {
            JsonNode s = admin.post("COUSR03C", shortRead, Map.of("userId", "USER0001"));
            assertThat(s.at("/message").asText()).isEqualTo(CommonMessages.INVALID_KEY);
            assertThat(s.at("/fields/userId").asText()).isEqualTo("USER0001");
            assertThat(s.at("/fields/firstName").asText()).isEqualTo(fetched.at("/fields/firstName").asText());
            assertThat(s.at("/focus").asText()).isEqualTo("userId");
        }
        assertThat(exists("USER0001")).isTrue();
    }

    @Test
    @Covers({"TS-COUSR03C-12", "Q-COUSR03C-02"})
    void an_admin_cannot_delete_their_own_user_id() {
        admin.show("COUSR03C");
        admin.post("COUSR03C", "ENTER", Map.of("userId", "ADMIN001"));
        JsonNode r = admin.post("COUSR03C", "PF5", Map.of("userId", "ADMIN001"));
        assertThat(r.at("/message").asText()).isEqualTo("You cannot delete your own User ID...");
        assertThat(r.at("/messageColor").asText()).isEqualTo("RED");
        assertThat(exists("ADMIN001")).isTrue();
        assertThat(admin.showRaw("COADM01C").getStatus()).isEqualTo(200);
    }

    @Test
    @Covers({"ERR-COUSR03C-04", "Q-COUSR03C-03"})
    void a_user_deleted_by_someone_else_after_the_fetch_is_not_found() {
        admin.show("COUSR03C");
        admin.post("COUSR03C", "ENTER", Map.of("userId", "USER0002"));
        jdbc.update("DELETE FROM app_user WHERE user_id = 'USER0002'");
        JsonNode r = admin.post("COUSR03C", "PF5", Map.of("userId", "USER0002"));
        assertThat(r.at("/message").asText()).isEqualTo("User ID NOT found...");
        assertThat(r.at("/focus").asText()).isEqualTo("userId");
        assertThat(audits("USER_DELETE", "USER0002")).isZero();
    }

    @Test
    @Covers({"BR-COUSR03C-01", "Q-COUSR03C-07", "C-ROLE"})
    void delete_user_requires_an_admin_session() {
        assertThat(new ScreenClient(mvc).showRaw("COUSR03C").getStatus()).isEqualTo(401);
        ScreenClient user = signedOn("USER0001");
        assertThat(user.turn("COUSR03C", "PF5", Map.of("userId", "USER0002")).getStatus()).isEqualTo(403);
        assertThat(exists("USER0002")).isTrue();
    }

    @Test
    @Covers({"ERR-COUSR03C-06"})
    void a_turn_with_no_fields_is_handled_as_blank_input() {
        admin.show("COUSR03C");
        assertThat(admin.post("COUSR03C", "ENTER", Map.of()).at("/message").asText()).isEqualTo("User ID can NOT be empty...");
    }
}
