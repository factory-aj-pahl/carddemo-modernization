package com.carddemo.iam;

import static org.assertj.core.api.Assertions.assertThat;

import com.carddemo.common.msg.CommonMessages;
import com.carddemo.test.Covers;
import com.carddemo.test.ScreenClient;
import com.carddemo.test.TestApplication;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpServletResponse;

@SpringBootTest(classes = TestApplication.class)
@AutoConfigureMockMvc
class UserListTest extends UserScreenTestSupport {

    static final List<String> SAMPLE = List.of("ADMIN001", "ADMIN002", "ADMIN003", "ADMIN004", "ADMIN005",
        "USER0001", "USER0002", "USER0003", "USER0004", "USER0005");

    @Test
    @Covers({"BR-COUSR00C-02", "BR-COUSR00C-03", "BR-COUSR00C-04", "BR-COUSR00C-11", "BR-COUSR00C-14", "TS-COUSR00C-01",
        "Q-COUSR00C-02", "ERR-COUSR00C-03"})
    void first_entry_lists_the_ten_sample_users_on_page_one() {
        JsonNode r = admin.show("COUSR00C");
        assertThat(userIds(r)).containsExactlyElementsOf(SAMPLE);
        assertThat(r.at("/fields/rows/0/userType").asText()).isEqualTo("A");
        assertThat(r.at("/fields/rows/9/userType").asText()).isEqualTo("U");
        assertThat(r.at("/fields/rows/5/firstName").asText()).isNotBlank();
        assertThat(r.at("/fields/rows/0").has("password")).isFalse();
        assertThat(r.toString()).doesNotContain("$2a$");
        assertThat(r.at("/fields/pageNum").asText()).isEqualTo("00000001");
        assertThat(r.at("/message").asText()).isEqualTo("You have reached the bottom of the page...");
        assertThat(r.at("/focus").asText()).isEqualTo("searchUserId");
    }

    @Test
    @Covers({"NAV-COUSR00C-04", "TS-COUSR00C-02", "NAV-COUSR00C-03", "TS-COUSR00C-03", "NAV-COUSR00C-07"})
    void paging_past_either_end_keeps_the_rows() {
        admin.show("COUSR00C");
        JsonNode down = admin.post("COUSR00C", "PF8", Map.of());
        assertThat(userIds(down)).containsExactlyElementsOf(SAMPLE);
        assertThat(down.at("/message").asText()).isEqualTo("You are already at the bottom of the page...");
        JsonNode up = admin.post("COUSR00C", "PF7", Map.of());
        assertThat(userIds(up)).containsExactlyElementsOf(SAMPLE);
        assertThat(up.at("/message").asText()).isEqualTo("You are already at the top of the page...");
        assertThat(up.at("/fields/pageNum").asText()).isEqualTo("00000001");
    }

    @Test
    @Covers({"Q-COUSR00C-15", "TS-COUSR00C-17", "NAV-COUSR00C-03", "NAV-COUSR00C-04"})
    void pf7_on_page_one_does_not_mark_a_next_page() {
        admin.show("COUSR00C");
        JsonNode up = admin.post("COUSR00C", "PF7", Map.of());
        assertThat(up.at("/message").asText()).isEqualTo("You are already at the top of the page...");
        JsonNode down = admin.post("COUSR00C", "PF8", Map.of());
        assertThat(down.at("/message").asText()).isEqualTo("You are already at the bottom of the page...");
        assertThat(userIds(down)).containsExactlyElementsOf(SAMPLE);
        assertThat(down.at("/fields/pageNum").asText()).isEqualTo("00000001");

        twentyFiveUsers();
        admin.show("COUSR00C");
        admin.post("COUSR00C", "PF7", Map.of());
        JsonNode next = admin.post("COUSR00C", "PF8", Map.of());
        assertThat(userIds(next)).as("with more users F8 still pages as usual").containsExactlyElementsOf(range(11, 20));
        assertThat(next.at("/fields/pageNum").asText()).isEqualTo("00000002");
    }

    @Test
    @Covers({"BR-COUSR00C-09", "BR-COUSR00C-16", "TS-COUSR00C-04", "Q-COUSR00C-12"})
    void search_starts_the_list_at_the_key_and_clears_the_search_field() {
        admin.show("COUSR00C");
        JsonNode r = admin.post("COUSR00C", "ENTER", Map.of("searchUserId", "USER0003"));
        assertThat(userIds(r)).containsExactly("USER0003", "USER0004", "USER0005", "", "", "", "", "", "", "");
        assertThat(r.at("/fields/rows/5/firstName").asText()).isEmpty();
        assertThat(r.at("/fields/pageNum").asText()).isEqualTo("00000001");
        assertThat(r.at("/message").asText()).isEqualTo("You have reached the bottom of the page...");
        assertThat(r.at("/fields/searchUserId").asText()).isEmpty();
        JsonNode again = admin.post("COUSR00C", "ENTER", Map.of("searchUserId", ""));
        assertThat(userIds(again).get(0)).isEqualTo("ADMIN001");
    }

    @Test
    @Covers({"BR-COUSR00C-06", "NAV-COUSR00C-06", "NAV-COUSR00C-01", "TS-COUSR00C-05", "Q-COADM01C-05"})
    void u_on_row_two_opens_update_with_that_user() {
        admin.show("COUSR00C");
        JsonNode r = admin.post("COUSR00C", "ENTER", Map.of("sel", sel(2, "U")));
        assertThat(r.at("/navigate").asText()).isEqualTo("COUSR02C");
        JsonNode upd = admin.show("COUSR02C");
        assertThat(upd.at("/fields/userId").asText()).isEqualTo("ADMIN002");
        assertThat(upd.at("/message").asText()).isEqualTo("Press PF5 key to save your updates ...");
        Map<String, Object> unchanged = new java.util.HashMap<>();
        upd.get("fields").fields().forEachRemaining(e -> unchanged.put(e.getKey(), e.getValue().asText()));
        assertThat(admin.post("COUSR02C", "PF3", unchanged).at("/navigate").asText()).isEqualTo("COUSR00C");
    }

    @Test
    @Covers({"BR-COUSR00C-07", "BR-COUSR00C-05", "TS-COUSR00C-06"})
    void lower_case_d_selects_delete_and_lower_rows_are_ignored() {
        admin.show("COUSR00C");
        JsonNode r = admin.post("COUSR00C", "ENTER", Map.of("sel", sel(6, "d", 8, "U")));
        assertThat(r.at("/navigate").asText()).isEqualTo("COUSR03C");
        JsonNode del = admin.show("COUSR03C");
        assertThat(del.at("/fields/userId").asText()).isEqualTo("USER0001");
        assertThat(del.at("/message").asText()).isEqualTo("Press PF5 key to delete this user ...");
    }

    @Test
    @Covers({"BR-COUSR00C-08", "TS-COUSR00C-07"})
    void a_code_on_an_empty_row_is_ignored_and_the_list_rebuilt_from_the_first_user() {
        admin.show("COUSR00C");
        admin.post("COUSR00C", "ENTER", Map.of("searchUserId", "USER0003"));
        JsonNode r = admin.post("COUSR00C", "ENTER", Map.of("sel", sel(5, "U")));
        assertThat(r.has("navigate")).isFalse();
        assertThat(userIds(r)).containsExactlyElementsOf(SAMPLE);
        assertThat(r.at("/message").asText()).isNotEqualTo("Invalid selection. Valid values are U and D");
    }

    @Test
    @Covers({"Q-COUSR00C-14"})
    void selection_is_resolved_from_the_servers_page_not_from_submitted_ids() {
        admin.show("COUSR00C");
        JsonNode r = admin.post("COUSR00C", "ENTER", Map.of("sel", sel(2, "U"),
            "rows", List.of(Map.of("userId", "USER0005"), Map.of("userId", "USER0005")), "userId", "USER0005"));
        assertThat(r.at("/navigate").asText()).isEqualTo("COUSR02C");
        assertThat(admin.show("COUSR02C").at("/fields/userId").asText()).isEqualTo("ADMIN002");
    }

    @Test
    @Covers({"BR-COUSR00C-10", "BR-COUSR00C-11", "BR-COUSR00C-12", "BR-COUSR00C-13", "TS-COUSR00C-08", "Q-COUSR00C-04",
        "Q-COUSR00C-07", "ERR-COUSR00C-03", "ERR-COUSR00C-05"})
    void twenty_five_users_page_forward_and_back() {
        twentyFiveUsers();
        JsonNode p1 = admin.show("COUSR00C");
        assertThat(userIds(p1)).containsExactlyElementsOf(range(1, 10));
        assertThat(p1.has("message")).isFalse();
        JsonNode p2 = admin.post("COUSR00C", "PF8", Map.of());
        assertThat(userIds(p2)).containsExactlyElementsOf(range(11, 20));
        assertThat(p2.at("/fields/pageNum").asText()).isEqualTo("00000002");
        assertThat(p2.has("message")).isFalse();
        JsonNode p3 = admin.post("COUSR00C", "PF8", Map.of());
        assertThat(userIds(p3)).containsExactlyElementsOf(padded(range(21, 25)));
        assertThat(p3.at("/fields/pageNum").asText()).isEqualTo("00000003");
        assertThat(p3.at("/message").asText()).isEqualTo("You have reached the bottom of the page...");
        JsonNode again = admin.post("COUSR00C", "PF8", Map.of());
        assertThat(again.at("/message").asText()).isEqualTo("You are already at the bottom of the page...");
        assertThat(userIds(again)).containsExactlyElementsOf(padded(range(21, 25)));
        JsonNode back2 = admin.post("COUSR00C", "PF7", Map.of());
        assertThat(userIds(back2)).containsExactlyElementsOf(range(11, 20));
        assertThat(back2.at("/fields/pageNum").asText()).isEqualTo("00000002");
        assertThat(back2.has("message")).isFalse();
        JsonNode back1 = admin.post("COUSR00C", "PF7", Map.of());
        assertThat(userIds(back1)).containsExactlyElementsOf(range(1, 10));
        assertThat(back1.at("/fields/pageNum").asText()).isEqualTo("00000001");
        assertThat(back1.at("/message").asText()).isEqualTo("You have reached the top of the page...");
        JsonNode top = admin.post("COUSR00C", "PF7", Map.of());
        assertThat(top.at("/message").asText()).isEqualTo("You are already at the top of the page...");
    }

    @Test
    @Covers({"VR-COUSR00C-01", "TS-COUSR00C-09", "Q-COUSR00C-01", "Q-COUSR00C-06", "Q-COUSR00C-02"})
    void invalid_selection_rebuilds_page_one_with_cleared_codes() {
        twentyFiveUsers();
        admin.show("COUSR00C");
        admin.post("COUSR00C", "PF8", Map.of());
        JsonNode r = admin.post("COUSR00C", "ENTER", Map.of("sel", sel(1, "X")));
        assertThat(r.at("/message").asText()).isEqualTo("Invalid selection. Valid values are U and D");
        assertThat(r.at("/focus").asText()).isEqualTo("searchUserId");
        assertThat(userIds(r)).containsExactlyElementsOf(range(1, 10));
        assertThat(r.at("/fields/pageNum").asText()).isEqualTo("00000001");
        r.at("/fields/rows").forEach(row -> assertThat(row.get("sel").asText()).isEmpty());
        // With the 10 sample users the same ENTER also reaches the end of the file: the selection message wins.
        sample.load();
        ScreenClient c = signedOn("ADMIN002");
        c.show("COUSR00C");
        assertThat(c.post("COUSR00C", "ENTER", Map.of("sel", sel(3, "Z"))).at("/message").asText())
            .isEqualTo("Invalid selection. Valid values are U and D");
    }

    @Test
    @Covers({"BR-COUSR00C-09", "TS-COUSR00C-10", "Q-COUSR00C-05", "Q-COUSR00C-12"})
    void a_search_in_the_middle_restarts_page_numbers() {
        twentyFiveUsers();
        admin.show("COUSR00C");
        JsonNode r = admin.post("COUSR00C", "ENTER", Map.of("searchUserId", "t0000005"));
        assertThat(userIds(r)).containsExactlyElementsOf(range(5, 14));
        assertThat(r.at("/fields/pageNum").asText()).isEqualTo("00000001");
        assertThat(admin.post("COUSR00C", "PF7", Map.of()).at("/message").asText())
            .isEqualTo("You are already at the top of the page...");
    }

    @Test
    @Covers({"ERR-COUSR00C-01", "TS-COUSR00C-11", "Q-COUSR00C-03", "ERR-COUSR00C-07"})
    void a_search_beyond_the_last_user_shows_an_empty_list_and_not_found() {
        admin.show("COUSR00C");
        JsonNode r = admin.post("COUSR00C", "ENTER", Map.of("searchUserId", "ZZZZZZZZ"));
        assertThat(userIds(r)).containsOnly("");
        assertThat(r.at("/fields/pageNum").asText()).isEqualTo("00000001");
        assertThat(r.at("/message").asText()).isEqualTo("User ID NOT found...");
        assertThat(admin.post("COUSR00C", "PF8", Map.of()).at("/message").asText())
            .isEqualTo("You are already at the bottom of the page...");
    }

    @Test
    @Covers({"Q-COUSR00C-09"})
    void deleting_boundary_users_between_pages_hides_no_user_and_raises_no_error() {
        twentyFiveUsers();
        admin.show("COUSR00C");
        admin.post("COUSR00C", "PF8", Map.of());
        jdbc.update("DELETE FROM app_user WHERE user_id IN ('T0000011', 'T0000020')");
        JsonNode next = admin.post("COUSR00C", "PF8", Map.of());
        assertThat(userIds(next)).containsExactlyElementsOf(padded(range(21, 25)));
        assertThat(next.at("/message").asText()).isEqualTo("You have reached the bottom of the page...");
        jdbc.update("DELETE FROM app_user WHERE user_id = 'T0000021'");
        JsonNode back = admin.post("COUSR00C", "PF7", Map.of());
        assertThat(back.has("message")).isFalse();
        assertThat(userIds(back)).containsExactly("T0000009", "T0000010", "T0000012", "T0000013", "T0000014", "T0000015",
            "T0000016", "T0000017", "T0000018", "T0000019");
        assertThat(back.at("/fields/pageNum").asText()).isEqualTo("00000002");
    }

    @Test
    @Covers({"NAV-COUSR00C-02", "TS-COUSR00C-12", "Q-COUSR00C-11"})
    void pf3_returns_to_the_admin_menu() {
        admin.show("COUSR00C");
        assertThat(admin.post("COUSR00C", "PF3", Map.of()).at("/navigate").asText()).isEqualTo("COADM01C");
    }

    @Test
    @Covers({"NAV-COUSR00C-05", "TS-COUSR00C-13"})
    void other_keys_are_invalid_and_keep_the_rows() {
        admin.show("COUSR00C");
        JsonNode r = admin.post("COUSR00C", "PF5", Map.of("searchUserId", "USER", "sel", sel(4, "U")));
        assertThat(r.at("/message").asText()).isEqualTo(CommonMessages.INVALID_KEY);
        assertThat(userIds(r)).containsExactlyElementsOf(SAMPLE);
        assertThat(r.at("/fields/rows/3/sel").asText()).isEqualTo("U");
        assertThat(r.at("/fields/searchUserId").asText()).isEqualTo("USER");
        assertThat(r.has("navigate")).isFalse();
    }

    @Test
    @Covers({"BR-COUSR00C-01", "TS-COUSR00C-14", "Q-COUSR00C-10", "C-ROLE"})
    void the_list_requires_an_admin_session() {
        assertThat(new ScreenClient(mvc).showRaw("COUSR00C").getStatus()).isEqualTo(401);
        ScreenClient user = signedOn("USER0001");
        MockHttpServletResponse res = user.showRaw("COUSR00C");
        assertThat(res.getStatus()).isEqualTo(403);
        JsonNode body = user.body(res);
        assertThat(body.at("/navigate").asText()).isEqualTo("COMEN01C");
        assertThat(body.at("/message").asText()).isEqualTo(CommonMessages.ADMIN_ONLY);
        assertThat(user.turn("COUSR00C", "ENTER", Map.of()).getStatus()).isEqualTo(403);
    }

    @Test
    @Covers({"BR-COUSR00C-02", "TS-COUSR00C-16", "Q-COUSR00C-07"})
    void returning_from_update_shows_page_one_from_the_first_user() {
        twentyFiveUsers();
        admin.show("COUSR00C");
        admin.post("COUSR00C", "PF8", Map.of());
        admin.post("COUSR00C", "ENTER", Map.of("sel", sel(1, "U")));
        JsonNode upd = admin.show("COUSR02C");
        assertThat(upd.at("/fields/userId").asText()).isEqualTo("T0000011");
        Map<String, Object> unchanged = new java.util.HashMap<>();
        upd.get("fields").fields().forEachRemaining(e -> unchanged.put(e.getKey(), e.getValue().asText()));
        assertThat(admin.post("COUSR02C", "PF3", unchanged).at("/navigate").asText()).isEqualTo("COUSR00C");
        JsonNode back = admin.show("COUSR00C");
        assertThat(userIds(back).get(0)).isEqualTo("T0000001");
        assertThat(back.at("/fields/pageNum").asText()).isEqualTo("00000001");
    }

    @Test
    @Covers({"ERR-COUSR00C-08"})
    void a_turn_with_no_fields_is_handled_as_blank_input() {
        admin.show("COUSR00C");
        JsonNode r = admin.post("COUSR00C", "ENTER", Map.of());
        assertThat(userIds(r)).containsExactlyElementsOf(SAMPLE);
    }
}
