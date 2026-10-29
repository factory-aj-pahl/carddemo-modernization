package com.carddemo.refdata;

import static com.carddemo.refdata.TranTypeScreens.describe;
import static com.carddemo.refdata.TranTypeScreens.description;
import static com.carddemo.refdata.TranTypeScreens.listInput;
import static com.carddemo.refdata.TranTypeScreens.row;
import static com.carddemo.refdata.TranTypeScreens.select;
import static com.carddemo.refdata.TranTypeScreens.types;
import static org.assertj.core.api.Assertions.assertThat;

import com.carddemo.common.msg.CommonMessages;
import com.carddemo.test.Covers;
import com.carddemo.test.SampleData;
import com.carddemo.test.ScreenClient;
import com.carddemo.test.TestApplication;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/** COTRTLIC screen turns against the Db2 seed types and categories (spec test assumptions). */
@SpringBootTest(classes = TestApplication.class)
@AutoConfigureMockMvc
class TranTypeListScreenTest {

    static final String P = "COTRTLIC";
    static final List<String> SEED = List.of("01", "02", "03", "04", "05", "06", "07");
    static final String INFO_ACTIONS = "  Type U to update, D to delete any record   ";

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
        TranTypeScreens.loadDb2Seed(jdbc);
        client = new ScreenClient(mvc);
        client.signOn("ADMIN001");
    }

    private void addTypes(String... codeAndDesc) {
        for (int i = 0; i < codeAndDesc.length; i += 2) {
            jdbc.update("INSERT INTO transaction_type (type_cd, description) VALUES (?, ?)", codeAndDesc[i], codeAndDesc[i + 1]);
        }
    }

    private JsonNode key(String key, JsonNode screen) {
        return client.post(P, key, listInput(screen));
    }

    @Test
    @Covers({"TS-COTRTLIC-01", "BR-COTRTLIC-01", "BR-COTRTLIC-02", "BR-COTRTLIC-03", "BR-COTRTLIC-04", "BR-COTRTLIC-05",
        "BR-COTRTLIC-17", "BR-COTRTLIC-18", "BR-COTRTLIC-20", "NAV-COTRTLIC-07"})
    void first_entry_lists_page_one_of_the_seed_types() {
        assertThat(client.post("COADM01C", "ENTER", Map.of("option", "5")).at("/navigate").asText()).isEqualTo(P);
        JsonNode r = client.show(P);
        assertThat(types(r)).isEqualTo(SEED);
        assertThat(row(r, 6).get("description").asText()).isEqualTo("REVERAL");
        assertThat(r.at("/fields/page").asText()).isEqualTo("1");
        assertThat(r.at("/fields/info").asText()).isEqualTo(INFO_ACTIONS).hasSize(45);
        assertThat(r.has("message")).isFalse();
        assertThat(r.has("navigate")).isFalse();
        assertThat(r.at("/focus").asText()).isEqualTo("typeFilter");
        assertThat(r.at("/fields/typeFilter").asText()).isEmpty();
        assertThat(r.at("/fields/filtersLocked").asBoolean()).isFalse();
        assertThat(row(r, 1).get("selectable").asBoolean()).isTrue();
        assertThat(row(r, 1).get("descriptionEditable").asBoolean()).isFalse();
    }

    @Test
    @Covers({"TS-COTRTLIC-02", "BR-COTRTLIC-07", "NAV-COTRTLIC-03"})
    void pf8_without_a_next_page_gives_the_last_page_messages() {
        JsonNode r = key("PF8", client.show(P));
        assertThat(types(r)).isEqualTo(SEED);
        assertThat(r.at("/message").asText()).isEqualTo("No more pages for these search conditions");
        assertThat(r.at("/fields/page").asText()).isEqualTo("1");
        r = key("PF8", r);
        assertThat(r.at("/message").asText()).isEqualTo("No more pages to display");
        assertThat(types(r)).isEqualTo(SEED);
    }

    @Test
    @Covers({"TS-COTRTLIC-03", "Q-COTRTLIC-06"})
    void pf7_on_page_one_rereads_it_with_the_first_page_message() {
        JsonNode r = key("PF7", client.show(P));
        assertThat(types(r)).isEqualTo(SEED);
        assertThat(r.at("/message").asText()).isEqualTo("No previous pages to display");
        assertThat(r.at("/fields/page").asText()).isEqualTo("1");
    }

    @Test
    @Covers({"TS-COTRTLIC-04", "BR-COTRTLIC-06", "Q-COTRTLIC-20"})
    void paging_forward_and_back_shows_boundary_messages_only_when_pressed_at_the_boundary() {
        addTypes("08", "TEST A", "09", "TEST B", "10", "TEST C");
        JsonNode r = key("PF8", client.show(P));
        assertThat(types(r)).containsExactly("08", "09", "10");
        assertThat(r.at("/fields/page").asText()).isEqualTo("2");
        assertThat(r.has("message")).isFalse();
        assertThat(r.at("/fields/info").asText()).isEqualTo(INFO_ACTIONS);
        assertThat(row(r, 4).get("selectable").asBoolean()).isFalse();
        JsonNode again = key("PF8", r);
        assertThat(again.at("/message").asText()).isEqualTo("No more pages to display");
        assertThat(types(again)).containsExactly("08", "09", "10");
        r = key("PF7", again);
        assertThat(types(r)).isEqualTo(SEED);
        assertThat(r.at("/fields/page").asText()).isEqualTo("1");
        assertThat(r.has("message")).isFalse();
        r = key("PF7", r);
        assertThat(r.at("/message").asText()).isEqualTo("No previous pages to display");
    }

    @Test
    @Covers({"Q-COTRTLIC-06"})
    void page_numbers_go_past_nine() {
        for (int t = 8; t <= 99; t++) {
            addTypes(String.format("%02d", t), "TYPE " + t);
        }
        JsonNode r = client.show(P);
        for (int i = 0; i < 10; i++) {
            r = key("PF8", r);
        }
        assertThat(r.at("/fields/page").asText()).isEqualTo("11");
        assertThat(types(r).get(0)).isEqualTo("71");
        r = key("PF7", r);
        assertThat(r.at("/fields/page").asText()).isEqualTo("10");
        assertThat(types(r).get(0)).isEqualTo("64");
        for (int i = 0; i < 4; i++) {
            r = key("PF8", r);
        }
        assertThat(r.at("/fields/page").asText()).isEqualTo("14");
        r = key("PF8", r);
        assertThat(r.at("/fields/page").asText()).isEqualTo("15");
        assertThat(types(r)).containsExactly("99");
    }

    @Test
    @Covers({"Q-COTRTLIC-07"})
    void backward_page_that_reaches_the_start_shows_a_full_first_page() {
        addTypes("08", "TEST A", "09", "TEST B");
        JsonNode r = key("PF8", client.show(P));
        assertThat(types(r)).containsExactly("08", "09");
        jdbc.update("DELETE FROM transaction_category WHERE type_cd = '03'");
        jdbc.update("DELETE FROM transaction_type WHERE type_cd = '03'");
        r = key("PF7", r);
        assertThat(types(r)).containsExactly("01", "02", "04", "05", "06", "07", "08");
        assertThat(r.at("/fields/page").asText()).isEqualTo("1");
        assertThat(r.has("message")).isFalse();
        r = key("PF8", r);
        assertThat(types(r)).containsExactly("09");
    }

    @Test
    @Covers({"TS-COTRTLIC-05", "BR-COTRTLIC-08", "BR-COTRTLIC-09", "BR-COTRTLIC-11"})
    void type_filter_lists_only_that_type() {
        Map<String, Object> in = listInput(client.show(P));
        in.put("typeFilter", "03");
        JsonNode r = client.post(P, "ENTER", in);
        assertThat(types(r)).containsExactly("03");
        assertThat(row(r, 1).get("description").asText()).isEqualTo("CREDIT");
        assertThat(r.at("/fields/page").asText()).isEqualTo("1");
        assertThat(r.at("/fields/typeFilter").asText()).isEqualTo("03");
        assertThat(r.has("message")).isFalse();
        in = listInput(r);
        in.put("typeFilter", "");
        assertThat(types(client.post(P, "ENTER", in))).isEqualTo(SEED);
    }

    @Test
    @Covers({"BR-COTRTLIC-20"})
    void zero_type_filter_means_no_filter_and_is_cleared() {
        Map<String, Object> in = listInput(client.show(P));
        in.put("typeFilter", "00");
        JsonNode r = client.post(P, "ENTER", in);
        assertThat(types(r)).isEqualTo(SEED);
        assertThat(r.at("/fields/typeFilter").asText()).isEmpty();
        assertThat(r.has("message")).isFalse();
    }

    @Test
    @Covers({"TS-COTRTLIC-06", "VR-COTRTLIC-01", "Q-COTRTLIC-21"})
    void non_numeric_type_filter_is_rejected_and_keeps_rows_and_page() {
        addTypes("08", "TEST A");
        JsonNode page2 = key("PF8", client.show(P));
        for (String bad : new String[] {"A1", "5"}) {
            Map<String, Object> in = listInput(page2);
            in.put("typeFilter", bad);
            JsonNode r = client.post(P, "ENTER", in);
            assertThat(r.at("/message").asText()).isEqualTo("TYPE CODE FILTER,IF SUPPLIED MUST BE A 2 DIGIT NUMBER");
            assertThat(r.at("/messageColor").asText()).isEqualTo("RED");
            assertThat(r.at("/fields/typeFilterInvalid").asBoolean()).isTrue();
            assertThat(r.at("/fields/typeFilter").asText()).isEqualTo(bad);
            assertThat(r.at("/focus").asText()).isEqualTo("typeFilter");
            assertThat(types(r)).containsExactly("08");
            assertThat(row(r, 1).get("selectable").asBoolean()).isFalse();
            assertThat(r.at("/fields/page").asText()).isEqualTo("2");
            assertThat(r.at("/fields/info").isMissingNode() || r.at("/fields/info").isNull()).isTrue();
        }
    }

    @Test
    @Covers({"TS-COTRTLIC-07", "VR-COTRTLIC-02"})
    void type_filter_without_matches_is_flagged() {
        Map<String, Object> in = listInput(client.show(P));
        in.put("typeFilter", "99");
        JsonNode r = client.post(P, "ENTER", in);
        assertThat(r.at("/message").asText()).isEqualTo("No Records found for these filter conditions");
        assertThat(r.at("/fields/typeFilterInvalid").asBoolean()).isTrue();
        assertThat(r.at("/focus").asText()).isEqualTo("typeFilter");
        assertThat(types(r)).isEqualTo(SEED);
        assertThat(row(r, 1).get("selectable").asBoolean()).isFalse();
    }

    @Test
    @Covers({"TS-COTRTLIC-08", "BR-COTRTLIC-10"})
    void description_filter_is_a_case_sensitive_substring() {
        Map<String, Object> in = listInput(client.show(P));
        in.put("descFilter", "PAY");
        JsonNode r = client.post(P, "ENTER", in);
        assertThat(types(r)).containsExactly("02");
        assertThat(r.at("/fields/descFilter").asText()).isEqualTo("PAY");
        in = listInput(r);
        in.put("descFilter", "pay");
        r = client.post(P, "ENTER", in);
        assertThat(r.at("/message").asText()).isEqualTo("No Records found for these filter conditions");
        assertThat(r.at("/fields/descFilterInvalid").asBoolean()).isTrue();
        assertThat(r.at("/focus").asText()).isEqualTo("descFilter");
        in = listInput(r);
        in.put("descFilter", "R_F");
        assertThat(types(client.post(P, "ENTER", in))).containsExactly("05");
    }

    @Test
    @Covers({"TS-COTRTLIC-09", "VR-COTRTLIC-03", "BR-COTRTLIC-19", "BR-COTRTLIC-21"})
    void invalid_action_code_is_flagged_on_its_row() {
        JsonNode r = client.post(P, "ENTER", select(listInput(client.show(P)), 2, "X"));
        assertThat(r.at("/message").asText()).isEqualTo("Action code selected is invalid");
        assertThat(row(r, 2).get("selectInvalid").asBoolean()).isTrue();
        assertThat(row(r, 2).get("select").asText()).isEqualTo("X");
        assertThat(r.at("/focus").asText()).isEqualTo("select2");
        assertThat(r.at("/fields/filtersLocked").asBoolean()).isTrue();
        assertThat(description(jdbc, "02")).isEqualTo("PAYMENT");
        r = client.post(P, "ENTER", select(listInput(r), 2, "d"));
        assertThat(r.at("/message").asText()).isEqualTo("Action code selected is invalid");
    }

    @Test
    @Covers({"TS-COTRTLIC-10", "VR-COTRTLIC-04", "BR-COTRTLIC-12", "VR-COTRTLIC-08"})
    void more_than_one_action_is_rejected() {
        Map<String, Object> in = select(select(listInput(client.show(P)), 1, "D"), 3, "D");
        JsonNode r = client.post(P, "ENTER", in);
        assertThat(r.at("/message").asText()).isEqualTo("Please select only 1 action");
        assertThat(row(r, 1).get("selectInvalid").asBoolean()).isTrue();
        assertThat(row(r, 3).get("selectInvalid").asBoolean()).isTrue();
        assertThat(row(r, 1).get("highlighted").asBoolean()).isFalse();
        assertThat(r.at("/focus").asText()).isEqualTo("select1");
        // VR-COTRTLIC-08: VR-04 replaces VR-03; VR-01 replaces both.
        r = client.post(P, "ENTER", select(select(listInput(client.show(P)), 1, "D"), 2, "X"));
        assertThat(r.at("/message").asText()).isEqualTo("Please select only 1 action");
        in = select(listInput(client.show(P)), 2, "X");
        in.put("typeFilter", "A1");
        r = client.post(P, "ENTER", in);
        assertThat(r.at("/message").asText()).isEqualTo("TYPE CODE FILTER,IF SUPPLIED MUST BE A 2 DIGIT NUMBER");
    }

    @Test
    @Covers({"TS-COTRTLIC-11", "BR-COTRTLIC-13", "ERR-COTRTLIC-13", "ERR-COTRTLIC-02", "ERR-COTRTLIC-03", "NAV-COTRTLIC-04",
        "Q-COTRTLIC-13", "Q-COTRTLIC-17"})
    void delete_of_a_type_with_categories_is_refused() {
        JsonNode r = client.post(P, "ENTER", select(listInput(client.show(P)), 7, "D"));
        assertThat(row(r, 7).get("highlighted").asBoolean()).isTrue();
        assertThat(r.at("/fields/info").asText().strip()).isEqualTo("Delete HIGHLIGHTED row ? Press F10 to confirm");
        assertThat(r.at("/focus").asText()).isEqualTo("select7");
        assertThat(r.at("/fields/filtersLocked").asBoolean()).isTrue();
        assertThat(r.has("message")).isFalse();
        r = key("PF10", r);
        String m = r.at("/message").asText();
        assertThat(m).startsWith("Please delete associated child records first: SQLCODE: -532 ");
        assertThat(m).contains("The DELETE stat").doesNotContain("DSNTIAC");
        assertThat(m).hasSizeLessThanOrEqualTo(75);
        assertThat(description(jdbc, "07")).isEqualTo("ADJUSTMENT");
        assertThat(row(r, 7).get("highlighted").asBoolean()).isTrue();
    }

    @Test
    @Covers({"Q-COTRTLIC-23", "ERR-COTRTLIC-13"})
    void a_failed_delete_stays_armed_and_f10_retries_it_without_a_new_prompt() {
        JsonNode r = client.post(P, "ENTER", select(listInput(client.show(P)), 7, "D"));
        assertThat(r.at("/fields/info").asText().strip()).isEqualTo("Delete HIGHLIGHTED row ? Press F10 to confirm");
        r = key("PF10", r);
        assertThat(r.at("/message").asText()).startsWith("Please delete associated child records first: SQLCODE: -532 ");
        assertThat(r.at("/fields/info").asText()).doesNotContain("Press F10 to confirm");
        assertThat(row(r, 7).get("select").asText()).isEqualTo("D");
        assertThat(description(jdbc, "07")).isEqualTo("ADJUSTMENT");
        jdbc.update("DELETE FROM transaction_category WHERE type_cd = '07'");
        r = key("PF10", r);
        assertThat(r.at("/fields/info").asText().strip()).isEqualTo("HIGHLIGHTED row deleted.Hit Enter to continue");
        assertThat(r.has("message")).isFalse();
        assertThat(description(jdbc, "07")).isNull();
        assertThat(types(r)).doesNotContain("07");
    }

    @Test
    @Covers({"TS-COTRTLIC-12", "Q-COTRTLIC-04"})
    void delete_of_a_type_without_categories_removes_it_and_keeps_the_user() {
        addTypes("08", "TEST A");
        JsonNode r = key("PF8", client.show(P));
        r = client.post(P, "ENTER", select(listInput(r), 1, "D"));
        assertThat(r.at("/fields/info").asText().strip()).isEqualTo("Delete HIGHLIGHTED row ? Press F10 to confirm");
        r = key("PF10", r);
        assertThat(r.at("/fields/info").asText().strip()).isEqualTo("HIGHLIGHTED row deleted.Hit Enter to continue");
        assertThat(description(jdbc, "08")).isNull();
        assertThat(types(r)).isEqualTo(SEED);
        assertThat(r.at("/fields/page").asText()).isEqualTo("1");
        assertThat(r.has("message")).isFalse();
        r = key("ENTER", r);
        assertThat(types(r)).isEqualTo(SEED);
        assertThat(client.post(P, "PF3", listInput(r)).at("/navigate").asText()).isEqualTo("COADM01C");
        assertThat(client.show("COADM01C").at("/fields/options")).hasSize(6);
    }

    @Test
    @Covers({"TS-COTRTLIC-13", "BR-COTRTLIC-14", "VR-COTRTLIC-05", "Q-COTRTLIC-10", "Q-COTRTLIC-15", "Q-COTRTLIC-18"})
    void update_in_place_saves_the_new_description() {
        JsonNode r = client.post(P, "ENTER", select(listInput(client.show(P)), 5, "U"));
        assertThat(row(r, 5).get("descriptionEditable").asBoolean()).isTrue();
        assertThat(row(r, 5).get("highlighted").asBoolean()).isTrue();
        assertThat(r.at("/fields/info").asText().strip()).isEqualTo("Update HIGHLIGHTED row. Press F10 to save");
        assertThat(r.at("/message").asText()).isEqualTo("No change detected with respect to database values.");
        assertThat(r.at("/focus").asText()).isEqualTo("desc5");
        r = client.post(P, "PF10", describe(listInput(r), 5, "REFUND CREDIT"));
        assertThat(r.at("/fields/info").asText().strip()).isEqualTo("HIGHLIGHTED row was updated");
        assertThat(r.has("message")).isFalse();
        assertThat(description(jdbc, "05")).isEqualTo("REFUND CREDIT");
        assertThat(row(r, 5).get("description").asText()).isEqualTo("REFUND CREDIT");
        assertThat(row(r, 5).get("descriptionEditable").asBoolean()).isFalse();
        assertThat(row(r, 5).get("select").asText()).isEmpty();
        assertThat(r.at("/focus").asText()).isEqualTo("select5");
    }

    @Test
    @Covers({"TS-COTRTLIC-14", "VR-COTRTLIC-07"})
    void update_rejects_punctuation() {
        JsonNode r = client.post(P, "ENTER", select(listInput(client.show(P)), 5, "U"));
        r = client.post(P, "PF10", describe(listInput(r), 5, "REFUND-CR"));
        assertThat(r.at("/message").asText()).isEqualTo("Transaction Desc can have numbers or alphabets only.");
        assertThat(row(r, 5).get("descriptionInvalid").asBoolean()).isTrue();
        assertThat(row(r, 5).get("description").asText()).isEqualTo("REFUND-CR");
        assertThat(r.at("/focus").asText()).isEqualTo("desc5");
        assertThat(description(jdbc, "05")).isEqualTo("REFUND");
    }

    @Test
    @Covers({"TS-COTRTLIC-15", "VR-COTRTLIC-06"})
    void update_requires_a_description() {
        JsonNode r = client.post(P, "ENTER", select(listInput(client.show(P)), 5, "U"));
        r = client.post(P, "ENTER", describe(listInput(r), 5, ""));
        assertThat(r.at("/message").asText()).isEqualTo("Transaction Desc must be supplied.");
        assertThat(row(r, 5).get("description").asText()).isEqualTo("*");
        assertThat(row(r, 5).get("descriptionInvalid").asBoolean()).isTrue();
        assertThat(description(jdbc, "05")).isEqualTo("REFUND");
    }

    @Test
    @Covers({"TS-COTRTLIC-16", "BR-COTRTLIC-15", "BR-COTRTLIC-16", "NAV-COTRTLIC-05", "ERR-COTRTLIC-15"})
    void unaccepted_keys_act_as_enter() {
        JsonNode first = client.show(P);
        for (String k : new String[] {"PF10", "PF5", "CLEAR", "PA1"}) {
            JsonNode r = key(k, first);
            assertThat(types(r)).isEqualTo(SEED);
            assertThat(r.has("message")).isFalse();
            assertThat(r.has("navigate")).isFalse();
        }
        // A turn without any screen fields is processed as blank input.
        JsonNode r = client.post(P, "ENTER", Map.of());
        assertThat(types(r)).isEqualTo(SEED);
        assertThat(r.has("message")).isFalse();
        // PF10 after the selected row changed is ENTER: the new row gets the prompt, nothing is deleted.
        r = client.post(P, "ENTER", select(listInput(first), 7, "D"));
        r = client.post(P, "PF10", select(select(listInput(r), 7, ""), 6, "D"));
        assertThat(r.at("/fields/info").asText().strip()).isEqualTo("Delete HIGHLIGHTED row ? Press F10 to confirm");
        assertThat(row(r, 6).get("highlighted").asBoolean()).isTrue();
        assertThat(description(jdbc, "06")).isEqualTo("REVERAL");
    }

    @Test
    @Covers({"NAV-COTRTLIC-06"})
    void pf13_to_pf24_act_as_pf1_to_pf12() {
        JsonNode r = key("PF20", client.show(P));
        assertThat(r.at("/message").asText()).isEqualTo("No more pages for these search conditions");
        assertThat(key("PF15", r).at("/navigate").asText()).isEqualTo("COADM01C");
    }

    @Test
    @Covers({"TS-COTRTLIC-17", "NAV-COTRTLIC-01"})
    void pf3_returns_to_the_admin_menu() {
        assertThat(key("PF3", client.show(P)).at("/navigate").asText()).isEqualTo("COADM01C");
    }

    @Test
    @Covers({"TS-COTRTLIC-18", "NAV-COTRTLIC-02"})
    void pf2_opens_the_maintenance_screen() {
        assertThat(key("PF2", client.show(P)).at("/navigate").asText()).isEqualTo("COTRTUPC");
    }

    private void deleteType(String code) {
        jdbc.update("DELETE FROM transaction_category WHERE type_cd = ?", code);
        jdbc.update("DELETE FROM transaction_type WHERE type_cd = ?", code);
    }

    private static void assertNothingPending(JsonNode r) {
        for (JsonNode row : r.at("/fields/rows")) {
            assertThat(row.get("select").asText()).isEmpty();
            assertThat(row.get("highlighted").asBoolean()).isFalse();
            assertThat(row.get("descriptionEditable").asBoolean()).isFalse();
        }
    }

    @Test
    @Covers({"TS-COTRTLIC-20", "ERR-COTRTLIC-10", "Q-COTRTLIC-25"})
    void update_of_a_row_deleted_meanwhile_reports_not_found_and_leaves_nothing_pending() {
        JsonNode r = client.post(P, "ENTER", select(listInput(client.show(P)), 5, "U"));
        deleteType("05");
        r = client.post(P, "PF10", describe(listInput(r), 5, "REFUND CREDIT"));
        assertThat(r.at("/message").asText()).startsWith("Record not found. Deleted by others ? SQLCODE:  100 ");
        assertThat(description(jdbc, "05")).isNull();
        // Q-COTRTLIC-25: the record now in row 5 is shown with its database values and is not selected.
        assertThat(types(r)).containsExactly("01", "02", "03", "04", "06", "07");
        assertThat(row(r, 5).get("type").asText()).isEqualTo("06");
        assertThat(row(r, 5).get("description").asText()).isEqualTo("REVERAL");
        assertNothingPending(r);
        assertThat(r.at("/fields/info").asText()).isEqualTo(INFO_ACTIONS);
        r = key("PF10", r);
        assertThat(description(jdbc, "06")).isEqualTo("REVERAL");
        assertThat(r.at("/fields/info").asText()).isEqualTo(INFO_ACTIONS);
        assertNothingPending(r);
    }

    @Test
    @Covers({"TS-COTRTLIC-20", "VR-COTRTLIC-08", "Q-COTRTLIC-25"})
    void unchanged_update_of_a_row_deleted_meanwhile_shows_no_change_and_leaves_nothing_pending() {
        JsonNode r = client.post(P, "ENTER", select(listInput(client.show(P)), 5, "U"));
        deleteType("05");
        r = key("PF10", r);
        assertThat(r.at("/message").asText()).isEqualTo("No change detected with respect to database values.");
        assertThat(row(r, 5).get("type").asText()).isEqualTo("06");
        assertNothingPending(r);
        key("PF10", r);
        assertThat(description(jdbc, "06")).isEqualTo("REVERAL");
    }

    @Test
    @Covers({"TS-COTRTLIC-24", "BR-COTRTLIC-13", "Q-COTRTLIC-23"})
    void delete_marked_with_a_paging_key_is_not_armed_until_its_prompt_is_shown() {
        deleteType("06");
        jdbc.update("INSERT INTO transaction_type (type_cd, description) VALUES ('06', 'REVERAL')");
        for (String paging : new String[] {"PF7", "PF8"}) {
            JsonNode r = client.post(P, paging, select(listInput(client.show(P)), 6, "D"));
            assertThat(r.at("/message").asText())
                .isEqualTo(paging.equals("PF7") ? "No previous pages to display" : "No more pages for these search conditions");
            assertThat(r.at("/fields/info").asText()).doesNotContain("Delete");
            assertThat(row(r, 6).get("select").asText()).isEqualTo("D");
            // F10 without the delete prompt is ENTER: the prompt is shown now and nothing is deleted.
            r = key("PF10", r);
            assertThat(description(jdbc, "06")).isEqualTo("REVERAL");
            assertThat(r.at("/fields/info").asText().strip()).isEqualTo("Delete HIGHLIGHTED row ? Press F10 to confirm");
            assertThat(row(r, 6).get("highlighted").asBoolean()).isTrue();
        }
        // After the prompt, F10 confirms.
        JsonNode r = client.post(P, "ENTER", select(listInput(client.show(P)), 6, "D"));
        r = key("PF10", r);
        assertThat(r.at("/fields/info").asText().strip()).isEqualTo("HIGHLIGHTED row deleted.Hit Enter to continue");
        assertThat(description(jdbc, "06")).isNull();
    }

    @Test
    @Covers({"Q-COTRTLIC-23", "BR-COTRTLIC-15"})
    void any_other_key_after_the_prompt_disarms_the_confirmation() {
        deleteType("06");
        jdbc.update("INSERT INTO transaction_type (type_cd, description) VALUES ('06', 'REVERAL')");
        JsonNode r = client.post(P, "ENTER", select(listInput(client.show(P)), 6, "D"));
        assertThat(r.at("/fields/info").asText().strip()).isEqualTo("Delete HIGHLIGHTED row ? Press F10 to confirm");
        r = key("PF8", r);
        assertThat(r.at("/message").asText()).isEqualTo("No more pages for these search conditions");
        assertThat(row(r, 6).get("select").asText()).isEqualTo("D");
        r = key("PF10", r);
        assertThat(description(jdbc, "06")).isEqualTo("REVERAL");
        assertThat(r.at("/fields/info").asText().strip()).isEqualTo("Delete HIGHLIGHTED row ? Press F10 to confirm");
    }

    @Test
    @Covers({"TS-COTRTLIC-25", "BR-COTRTLIC-15", "BR-COTRTLIC-14", "Q-COTRTLIC-23"})
    void f10_runs_only_the_action_that_was_prompted() {
        deleteType("06");
        jdbc.update("INSERT INTO transaction_type (type_cd, description) VALUES ('06', 'REVERAL')");
        JsonNode r = client.post(P, "ENTER", select(listInput(client.show(P)), 6, "U"));
        assertThat(r.at("/fields/info").asText().strip()).isEqualTo("Update HIGHLIGHTED row. Press F10 to save");
        r = client.post(P, "PF10", select(listInput(r), 6, "D"));
        assertThat(description(jdbc, "06")).isEqualTo("REVERAL");
        assertThat(r.at("/fields/info").asText().strip()).isEqualTo("Delete HIGHLIGHTED row ? Press F10 to confirm");
        assertThat(row(r, 6).get("highlighted").asBoolean()).isTrue();
        // The reverse: a delete prompt does not confirm an update typed afterwards.
        r = client.post(P, "ENTER", select(listInput(client.show(P)), 5, "D"));
        assertThat(r.at("/fields/info").asText().strip()).isEqualTo("Delete HIGHLIGHTED row ? Press F10 to confirm");
        r = client.post(P, "PF10", describe(select(listInput(r), 5, "U"), 5, "REFUND CREDIT"));
        assertThat(description(jdbc, "05")).isEqualTo("REFUND");
        assertThat(r.at("/fields/info").asText().strip()).isEqualTo("Update HIGHLIGHTED row. Press F10 to save");
        assertThat(row(r, 5).get("descriptionEditable").asBoolean()).isTrue();
        r = key("PF10", r);
        assertThat(r.at("/fields/info").asText().strip()).isEqualTo("HIGHLIGHTED row was updated");
        assertThat(description(jdbc, "05")).isEqualTo("REFUND CREDIT");
    }

    @Test
    @Covers({"Q-COTRTLIC-23", "VR-COTRTLIC-06", "VR-COTRTLIC-07"})
    void f10_that_fails_the_description_edit_disarms_so_the_next_f10_prompts_again() {
        String[][] failures = {
            {"Bad#Text", "Transaction Desc can have numbers or alphabets only."},
            {"", "Transaction Desc must be supplied."},
        };
        for (String[] failure : failures) {
            JsonNode r = client.post(P, "ENTER", describe(select(listInput(client.show(P)), 5, "U"), 5, "First Text"));
            assertThat(r.at("/fields/info").asText().strip()).isEqualTo("Update HIGHLIGHTED row. Press F10 to save");
            r = client.post(P, "PF10", describe(listInput(r), 5, failure[0]));
            assertThat(r.at("/message").asText()).isEqualTo(failure[1]);
            assertThat(r.at("/fields/info").asText()).isEqualTo(INFO_ACTIONS);
            assertThat(description(jdbc, "05")).isEqualTo("REFUND");
            // No prompt was shown on that turn, so this F10 is ENTER: validated, prompted, not saved.
            r = client.post(P, "PF10", describe(listInput(r), 5, "Never Prompted"));
            assertThat(r.has("message")).isFalse();
            assertThat(r.at("/fields/info").asText().strip()).isEqualTo("Update HIGHLIGHTED row. Press F10 to save");
            assertThat(row(r, 5).get("description").asText()).isEqualTo("Never Prompted");
            assertThat(row(r, 5).get("descriptionEditable").asBoolean()).isTrue();
            assertThat(description(jdbc, "05")).isEqualTo("REFUND");
            r = key("PF10", r);
            assertThat(r.at("/fields/info").asText().strip()).isEqualTo("HIGHLIGHTED row was updated");
            assertThat(description(jdbc, "05")).isEqualTo("Never Prompted");
            jdbc.update("UPDATE transaction_type SET description = 'REFUND' WHERE type_cd = '05'");
        }
    }

    private static final String PROMPT_DELETE = "Delete HIGHLIGHTED row ? Press F10 to confirm";
    private static final String DELETED = "HIGHLIGHTED row deleted.Hit Enter to continue";

    /** Type 06 without categories, so that a confirmed delete of it succeeds; the delete prompt shown for row 6. */
    private JsonNode deletePromptForRow6() {
        deleteType("06");
        jdbc.update("INSERT INTO transaction_type (type_cd, description) VALUES ('06', 'REVERAL')");
        JsonNode r = client.post(P, "ENTER", select(listInput(client.show(P)), 6, "D"));
        assertThat(r.at("/fields/info").asText().strip()).isEqualTo(PROMPT_DELETE);
        return r;
    }

    @Test
    @Covers({"Q-COTRTLIC-23", "VR-COTRTLIC-04"})
    void f10_with_a_second_action_selected_disarms_so_the_next_f10_prompts_again_before_deleting() {
        JsonNode r = deletePromptForRow6();
        r = client.post(P, "PF10", select(listInput(r), 7, "D"));
        assertThat(r.at("/message").asText()).isEqualTo("Please select only 1 action");
        assertThat(r.at("/fields/info").asText()).isEqualTo(INFO_ACTIONS);
        assertThat(row(r, 6).get("selectInvalid").asBoolean()).isTrue();
        assertThat(row(r, 7).get("selectInvalid").asBoolean()).isTrue();
        assertThat(description(jdbc, "06")).isEqualTo("REVERAL");
        assertThat(description(jdbc, "07")).isEqualTo("ADJUSTMENT");
        // No prompt was shown on that turn, so this F10 with only the original D is ENTER: prompted, not deleted.
        r = client.post(P, "PF10", select(listInput(r), 7, ""));
        assertThat(r.has("message")).isFalse();
        assertThat(r.at("/fields/info").asText().strip()).isEqualTo(PROMPT_DELETE);
        assertThat(row(r, 6).get("highlighted").asBoolean()).isTrue();
        assertThat(description(jdbc, "06")).isEqualTo("REVERAL");
        r = key("PF10", r);
        assertThat(r.at("/fields/info").asText().strip()).isEqualTo(DELETED);
        assertThat(description(jdbc, "06")).isNull();
        assertThat(description(jdbc, "07")).isEqualTo("ADJUSTMENT");
    }

    @Test
    @Covers({"Q-COTRTLIC-23", "VR-COTRTLIC-03", "VR-COTRTLIC-08"})
    void f10_with_an_invalid_action_code_disarms_so_the_next_f10_prompts_again_before_deleting() {
        JsonNode r = deletePromptForRow6();
        // VR-COTRTLIC-08: an invalid code on another row is a second action, so VR-04 replaces the VR-03 message.
        r = client.post(P, "PF10", select(listInput(r), 4, "X"));
        assertThat(r.at("/message").asText()).isEqualTo("Please select only 1 action");
        assertThat(r.at("/fields/info").asText()).isEqualTo(INFO_ACTIONS);
        assertThat(row(r, 4).get("selectInvalid").asBoolean()).isTrue();
        assertThat(description(jdbc, "06")).isEqualTo("REVERAL");
        assertThat(description(jdbc, "04")).isEqualTo("AUTHORIZATION");
        r = client.post(P, "PF10", select(listInput(r), 4, ""));
        assertThat(r.has("message")).isFalse();
        assertThat(r.at("/fields/info").asText().strip()).isEqualTo(PROMPT_DELETE);
        assertThat(description(jdbc, "06")).isEqualTo("REVERAL");
        // The prompted row's code changed to an invalid one: VR-03, nothing deleted, and the D typed again is prompted.
        r = client.post(P, "PF10", select(listInput(r), 6, "X"));
        assertThat(r.at("/message").asText()).isEqualTo("Action code selected is invalid");
        assertThat(r.at("/fields/info").asText()).isEqualTo(INFO_ACTIONS);
        assertThat(row(r, 6).get("selectInvalid").asBoolean()).isTrue();
        assertThat(description(jdbc, "06")).isEqualTo("REVERAL");
        r = client.post(P, "PF10", select(listInput(r), 6, "D"));
        assertThat(r.has("message")).isFalse();
        assertThat(r.at("/fields/info").asText().strip()).isEqualTo(PROMPT_DELETE);
        assertThat(row(r, 6).get("highlighted").asBoolean()).isTrue();
        assertThat(description(jdbc, "06")).isEqualTo("REVERAL");
        r = key("PF10", r);
        assertThat(r.at("/fields/info").asText().strip()).isEqualTo(DELETED);
        assertThat(description(jdbc, "06")).isNull();
    }

    @Test
    @Covers({"Q-COTRTLIC-23", "VR-COTRTLIC-01", "VR-COTRTLIC-02"})
    void f10_with_a_type_filter_in_error_after_a_delete_prompt_deletes_nothing_and_leaves_nothing_pending() {
        String[][] failures = {
            {"AB", "TYPE CODE FILTER,IF SUPPLIED MUST BE A 2 DIGIT NUMBER"},
            {"99", "No Records found for these filter conditions"},
        };
        for (String[] failure : failures) {
            JsonNode r = deletePromptForRow6();
            Map<String, Object> in = listInput(r);
            in.put("typeFilter", failure[0]);
            r = client.post(P, "PF10", in);
            assertThat(r.at("/message").asText()).isEqualTo(failure[1]);
            assertThat(r.at("/fields/typeFilterInvalid").asBoolean()).isTrue();
            assertThat(description(jdbc, "06")).isEqualTo("REVERAL");
            // The filter typed back is a filter change: the selections are cleared and nothing is pending.
            in = listInput(r);
            in.put("typeFilter", "");
            r = client.post(P, "PF10", in);
            assertThat(r.has("message")).isFalse();
            assertThat(types(r)).isEqualTo(SEED);
            assertThat(r.at("/fields/info").asText()).isEqualTo(INFO_ACTIONS);
            assertNothingPending(r);
            assertThat(description(jdbc, "06")).isEqualTo("REVERAL");
            r = key("PF10", r);
            assertThat(r.at("/fields/info").asText()).isEqualTo(INFO_ACTIONS);
            assertNothingPending(r);
            assertThat(description(jdbc, "06")).isEqualTo("REVERAL");
        }
    }

    @Test
    @Covers({"TS-COTRTLIC-26", "BR-COTRTLIC-08", "Q-COTRTLIC-24"})
    void pf7_with_a_filter_change_shows_page_one_of_the_new_search() {
        addTypes("08", "TEST A");
        Map<String, Object> in = listInput(key("PF8", client.show(P)));
        in.put("typeFilter", "03");
        JsonNode r = client.post(P, "PF7", in);
        assertThat(types(r)).containsExactly("03");
        assertThat(r.at("/fields/page").asText()).isEqualTo("1");
        assertThat(r.has("message")).isFalse();
        assertThat(r.at("/fields/info").asText()).isEqualTo(INFO_ACTIONS);
        // The new search is at its first page now: PF7 there gives the boundary message.
        r = key("PF7", r);
        assertThat(r.at("/message").asText()).isEqualTo("No previous pages to display");
    }

    @Test
    @Covers({"TS-COTRTLIC-27", "BR-COTRTLIC-17", "Q-COTRTLIC-24"})
    void pf8_with_a_filter_change_shows_page_one_of_the_new_search_without_a_boundary_message() {
        Map<String, Object> in = listInput(client.show(P));
        in.put("descFilter", "R");
        JsonNode r = client.post(P, "PF8", in);
        assertThat(types(r)).containsExactly("01", "03", "04", "05", "06");
        assertThat(r.at("/fields/page").asText()).isEqualTo("1");
        assertThat(r.has("message")).isFalse();
        assertThat(r.at("/fields/info").asText()).isEqualTo(INFO_ACTIONS);
        r = key("PF8", r);
        assertThat(r.at("/message").asText()).isEqualTo("No more pages for these search conditions");
        r = key("PF8", r);
        assertThat(r.at("/message").asText()).isEqualTo("No more pages to display");
        // A filter in error with PF8 is handled as with ENTER.
        in = listInput(r);
        in.put("typeFilter", "A1");
        r = client.post(P, "PF8", in);
        assertThat(r.at("/message").asText()).isEqualTo("TYPE CODE FILTER,IF SUPPLIED MUST BE A 2 DIGIT NUMBER");
        assertThat(r.at("/fields/page").asText()).isEqualTo("1");
    }

    @Test
    @Covers({"Q-COTRTLIC-11", "VR-COTRTLIC-05", "BR-COTRTLIC-14"})
    void case_only_edit_is_discarded_by_enter_and_saved_by_f10() {
        JsonNode r = client.post(P, "ENTER", select(listInput(client.show(P)), 5, "U"));
        r = client.post(P, "ENTER", describe(listInput(r), 5, "refund"));
        assertThat(r.at("/message").asText()).isEqualTo("No change detected with respect to database values.");
        assertThat(r.at("/fields/info").asText().strip()).isEqualTo("Update HIGHLIGHTED row. Press F10 to save");
        assertThat(row(r, 5).get("description").asText()).isEqualTo("REFUND");
        r = key("PF10", r);
        assertThat(r.at("/fields/info").asText().strip()).isEqualTo("HIGHLIGHTED row was updated");
        assertThat(description(jdbc, "05")).isEqualTo("REFUND");
        r = client.post(P, "ENTER", select(listInput(r), 5, "U"));
        r = client.post(P, "PF10", describe(listInput(r), 5, "Refund"));
        assertThat(r.at("/message").asText()).isEqualTo("No change detected with respect to database values.");
        assertThat(r.at("/fields/info").asText().strip()).isEqualTo("HIGHLIGHTED row was updated");
        assertThat(description(jdbc, "05")).isEqualTo("Refund");
        assertThat(row(r, 5).get("description").asText()).isEqualTo("Refund");
    }

    @Test
    @Covers({"Q-COTRTLIC-19"})
    void update_of_a_row_changed_meanwhile_is_rejected_and_the_row_shows_the_reread_values() {
        JsonNode r = client.post(P, "ENTER", select(listInput(client.show(P)), 5, "U"));
        jdbc.update("UPDATE transaction_type SET description = 'REFUND OTHER' WHERE type_cd = '05'");
        r = client.post(P, "PF10", describe(listInput(r), 5, "REFUND CREDIT"));
        assertThat(r.at("/message").asText()).isEqualTo("Deadlock. Someone else updating ?");
        assertThat(description(jdbc, "05")).isEqualTo("REFUND OTHER");
        // The list is re-read: the row shows the other session's value, still selected for update.
        assertThat(row(r, 5).get("description").asText()).isEqualTo("REFUND OTHER");
        assertThat(row(r, 5).get("select").asText()).isEqualTo("U");
        assertThat(row(r, 5).get("descriptionEditable").asBoolean()).isTrue();
        assertThat(row(r, 5).get("highlighted").asBoolean()).isTrue();
        // F10 again with what is shown writes nothing the user has not seen.
        r = key("PF10", r);
        assertThat(description(jdbc, "05")).isEqualTo("REFUND OTHER");
        assertThat(row(r, 5).get("description").asText()).isEqualTo("REFUND OTHER");
    }

    @Test
    @Covers({"Q-COTRTLIC-19"})
    void after_a_rejected_update_the_description_typed_again_is_saved() {
        JsonNode r = client.post(P, "ENTER", select(listInput(client.show(P)), 5, "U"));
        jdbc.update("UPDATE transaction_type SET description = 'REFUND OTHER' WHERE type_cd = '05'");
        r = client.post(P, "PF10", describe(listInput(r), 5, "REFUND CREDIT"));
        assertThat(r.at("/message").asText()).isEqualTo("Deadlock. Someone else updating ?");
        assertThat(row(r, 5).get("description").asText()).isEqualTo("REFUND OTHER");
        r = client.post(P, "PF10", describe(listInput(r), 5, "REFUND CREDIT"));
        assertThat(r.at("/fields/info").asText().strip()).isEqualTo("HIGHLIGHTED row was updated");
        assertThat(r.has("message")).isFalse();
        assertThat(description(jdbc, "05")).isEqualTo("REFUND CREDIT");
        assertThat(row(r, 5).get("description").asText()).isEqualTo("REFUND CREDIT");
    }

    @Test
    @Covers({"TS-COTRTLIC-21", "Q-COTRTLIC-01"})
    void filter_change_clears_row_selections() {
        Map<String, Object> in = select(listInput(client.show(P)), 3, "D");
        in.put("descFilter", "R");
        JsonNode r = client.post(P, "ENTER", in);
        assertThat(types(r)).containsExactly("01", "03", "04", "05", "06");
        for (JsonNode row : r.at("/fields/rows")) {
            assertThat(row.get("highlighted").asBoolean()).isFalse();
            assertThat(row.get("select").asText()).isEmpty();
        }
        assertThat(r.at("/fields/info").asText()).isEqualTo(INFO_ACTIONS);
        r = key("PF10", r);
        assertThat(types(r)).containsExactly("01", "03", "04", "05", "06");
        assertThat(description(jdbc, "04")).isEqualTo("AUTHORIZATION");
    }

    @Test
    @Covers({"TS-COTRTLIC-22", "Q-COTRTLIC-05"})
    void only_signed_on_administrators_get_the_list() {
        ScreenClient anonymous = new ScreenClient(mvc);
        assertThat(anonymous.showRaw(P).getStatus()).isEqualTo(401);
        ScreenClient user = new ScreenClient(mvc);
        user.signOn("USER0001");
        var res = user.showRaw(P);
        assertThat(res.getStatus()).isEqualTo(403);
        JsonNode body = user.body(res);
        assertThat(body.at("/navigate").asText()).isEqualTo("COMEN01C");
        assertThat(body.at("/message").asText()).isEqualTo(CommonMessages.ADMIN_ONLY);
        assertThat(user.turn(P, "ENTER", Map.of()).getStatus()).isEqualTo(403);
    }
}
