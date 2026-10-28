package com.carddemo.refdata;

import static com.carddemo.refdata.TranTypeScreens.description;
import static com.carddemo.refdata.TranTypeScreens.listInput;
import static com.carddemo.refdata.TranTypeScreens.types;
import static org.assertj.core.api.Assertions.assertThat;

import com.carddemo.common.msg.CommonMessages;
import com.carddemo.test.Covers;
import com.carddemo.test.SampleData;
import com.carddemo.test.ScreenClient;
import com.carddemo.test.TestApplication;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/** COTRTUPC screen turns against the Db2 seed types and categories (spec test assumptions). */
@SpringBootTest(classes = TestApplication.class)
@AutoConfigureMockMvc
class TranTypeMaintScreenTest {

    static final String P = "COTRTUPC";
    static final String PROMPT = "Enter transaction type to be maintained      ";
    static final String CHANGES = " Update transaction type details shown.      ";

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

    private JsonNode send(String key, String type, String desc) {
        Map<String, String> m = new HashMap<>();
        m.put("typeCode", type);
        m.put("description", desc);
        return client.post(P, key, m);
    }

    /** The key pressed with the screen returned as shown. */
    private JsonNode again(String key, JsonNode screen) {
        return send(key, screen.at("/fields/typeCode").asText(), screen.at("/fields/description").asText());
    }

    private JsonNode lookUp(String type) {
        client.show(P);
        return send("ENTER", type, "");
    }

    private static String info(JsonNode r) {
        return r.at("/fields/info").asText();
    }

    private static String msg(JsonNode r) {
        return r.at("/message").asText();
    }

    private static String keys(JsonNode r) {
        JsonNode k = r.at("/fields/keys");
        return (k.get("enterExit").asBoolean() ? "ENTER=Process F3=Exit " : "") + (k.get("delete").asBoolean() ? "F4 " : "")
            + (k.get("save").asBoolean() ? "F5 " : "") + (k.get("cancel").asBoolean() ? "F12" : "");
    }

    private static boolean flag(JsonNode r, String name) {
        return r.at("/fields/" + name).asBoolean();
    }

    @Test
    @Covers({"TS-COTRTUPC-01", "BR-COTRTUPC-02", "BR-COTRTUPC-21", "BR-COTRTUPC-22", "BR-COTRTUPC-23", "BR-COTRTUPC-26"})
    void option_6_shows_the_empty_prompt() {
        assertThat(client.post("COADM01C", "ENTER", Map.of("option", "6")).at("/navigate").asText()).isEqualTo(P);
        JsonNode r = client.show(P);
        assertThat(r.at("/fields/typeCode").asText()).isEmpty();
        assertThat(r.at("/fields/description").asText()).isEmpty();
        assertThat(info(r)).isEqualTo(PROMPT).hasSize(45);
        assertThat(keys(r)).isEqualTo("ENTER=Process F3=Exit ");
        assertThat(flag(r, "typeEditable")).isTrue();
        assertThat(flag(r, "descriptionEditable")).isFalse();
        assertThat(flag(r, "typeInvalid")).isFalse();
        assertThat(r.at("/focus").asText()).isEqualTo("typeCode");
        assertThat(r.has("message")).isFalse();
        assertThat(r.at("/fields/abend").isNull()).isTrue();
        // The signed-on user is kept: PF3 returns to the admin menu, which still serves ADMIN001.
        assertThat(send("PF3", "", "").at("/navigate").asText()).isEqualTo("COADM01C");
        assertThat(client.show("COADM01C").has("navigate")).isFalse();
    }

    @Test
    @Covers({"TS-COTRTUPC-02", "BR-COTRTUPC-05", "BR-COTRTUPC-10", "BR-COTRTUPC-20", "BR-COTRTUPC-24", "BR-COTRTUPC-27",
        "NAV-COTRTUPC-06", "Q-COTRTUPC-18"})
    void entering_1_shows_type_01_for_update() {
        JsonNode r = lookUp("1");
        assertThat(r.at("/fields/typeCode").asText()).isEqualTo("01");
        assertThat(r.at("/fields/description").asText()).isEqualTo("PURCHASE");
        assertThat(flag(r, "typeEditable")).isFalse();
        assertThat(flag(r, "descriptionEditable")).isTrue();
        assertThat(r.at("/focus").asText()).isEqualTo("description");
        assertThat(keys(r)).isEqualTo("ENTER=Process F3=Exit F4 F12");
        assertThat(info(r)).isEqualTo(CHANGES);
        assertThat(r.has("message")).isFalse();
        // The conversation state survives the turn: ENTER with the same values keeps the row shown.
        r = again("ENTER", r);
        assertThat(r.at("/fields/typeCode").asText()).isEqualTo("01");
        assertThat(msg(r)).isEqualTo("No change detected with respect to values fetched.");
        assertThat(lookUp(" 7").at("/fields/description").asText()).isEqualTo("ADJUSTMENT");
    }

    @Test
    @Covers({"TS-COTRTUPC-03", "VR-COTRTUPC-01", "VR-COTRTUPC-04", "BR-COTRTUPC-25", "BR-COTRTUPC-11", "ERR-COTRTUPC-11"})
    void a_blank_type_is_required_and_marked_with_a_red_star() {
        client.show(P);
        JsonNode r = send("ENTER", "  ", "");
        assertThat(msg(r)).isEqualTo("Tran Type code must be supplied.").isNotEqualTo("No input received");
        assertThat(r.at("/fields/typeCode").asText()).isEqualTo("*");
        assertThat(flag(r, "typeInvalid")).isTrue();
        assertThat(flag(r, "typeEditable")).isTrue();
        assertThat(r.at("/focus").asText()).isEqualTo("typeCode");
        assertThat(info(r)).isEqualTo(PROMPT);
        // No lookup was made: still waiting for a type, so F5 is not accepted.
        assertThat(send("PF5", "", "").at("/message").asText()).isEqualTo("Invalid key pressed");
        // A turn without any fields (nothing received) is the same blank type.
        r = client.post(P, "ENTER", Map.of());
        assertThat(msg(r)).isEqualTo("Tran Type code must be supplied.");
        assertThat(send("ENTER", "*", "").at("/message").asText()).isEqualTo("Tran Type code must be supplied.");
    }

    @Test
    @Covers({"TS-COTRTUPC-04", "TS-COTRTUPC-05", "TS-COTRTUPC-24", "VR-COTRTUPC-02", "VR-COTRTUPC-03", "Q-COTRTUPC-14"})
    void the_type_must_be_one_or_two_digits_from_01_to_99() {
        client.show(P);
        JsonNode r = send("ENTER", "A1", "");
        assertThat(msg(r)).isEqualTo("Tran Type code must be numeric.");
        assertThat(flag(r, "typeInvalid")).isTrue();
        assertThat(r.at("/focus").asText()).isEqualTo("typeCode");
        assertThat(msg(send("ENTER", "00", ""))).isEqualTo("Tran Type code must not be zero.");
        // Signs and decimal points no longer pass (TS-COTRTUPC-24 under the fix: -3 is not shown as 03 CREDIT).
        for (String bad : new String[] {"-3", "+1", "1.", ".5"}) {
            r = send("ENTER", bad, "");
            assertThat(msg(r)).as(bad).isEqualTo("Tran Type code must be numeric.");
            assertThat(r.at("/fields/description").asText()).isEmpty();
        }
        assertThat(send("ENTER", "3", "").at("/fields/description").asText()).isEqualTo("CREDIT");
    }

    @Test
    @Covers({"TS-COTRTUPC-06", "VR-COTRTUPC-08", "ERR-COTRTUPC-01", "BR-COTRTUPC-26"})
    void an_unknown_type_offers_the_add() {
        JsonNode r = lookUp("15");
        assertThat(msg(r)).isEqualTo("No record found for this key in database");
        assertThat(info(r).strip()).isEqualTo("Press F05 to add. F12 to cancel");
        assertThat(r.at("/fields/typeCode").asText()).isEqualTo("15");
        assertThat(flag(r, "typeInvalid")).isTrue();
        assertThat(flag(r, "typeEditable")).isTrue();
        assertThat(keys(r)).isEqualTo("ENTER=Process F3=Exit F5 F12");
        // PF12 in X goes back to the empty prompt.
        r = again("PF12", r);
        assertThat(r.at("/fields/typeCode").asText()).isEmpty();
        assertThat(info(r)).isEqualTo(PROMPT);
        assertThat(r.has("message")).isFalse();
    }

    @Test
    @Covers({"TS-COTRTUPC-07", "BR-COTRTUPC-16", "BR-COTRTUPC-09", "BR-COTRTUPC-17", "BR-COTRTUPC-18", "BR-COTRTUPC-19",
        "BR-COTRTUPC-07", "NAV-COTRTUPC-04"})
    void pf5_on_an_unknown_type_adds_it() {
        JsonNode r = again("PF5", lookUp("15"));
        assertThat(info(r).strip()).isEqualTo("Enter new transaction type details.");
        assertThat(r.at("/fields/typeCode").asText()).isEqualTo("15");
        assertThat(flag(r, "typeEditable")).isFalse();
        assertThat(flag(r, "descriptionEditable")).isTrue();
        assertThat(r.at("/focus").asText()).isEqualTo("description");
        assertThat(keys(r)).isEqualTo("ENTER=Process F3=Exit F12");
        // The type is not re-edited in add mode and a tampered type field is ignored.
        r = send("ENTER", "99", "INTEREST");
        assertThat(info(r).strip()).isEqualTo("Changes validated.Press F5 to save");
        assertThat(r.at("/fields/typeCode").asText()).isEqualTo("15");
        assertThat(flag(r, "typeEditable")).isFalse();
        assertThat(flag(r, "descriptionEditable")).isFalse();
        assertThat(keys(r)).isEqualTo("ENTER=Process F3=Exit F5 F12");
        assertThat(description(jdbc, "15")).isNull();
        r = again("PF5", r);
        assertThat(info(r).strip()).isEqualTo("Changes committed to database");
        assertThat(r.has("message")).isFalse();
        assertThat(flag(r, "typeEditable")).isFalse();
        assertThat(r.at("/focus").asText()).isEqualTo("typeCode");
        // Stored trimmed (Q-COTRTUPC-12, PLATFORM).
        assertThat(description(jdbc, "15")).isEqualTo("INTEREST");
        assertThat(description(jdbc, "99")).isNull();
        // The next key returns to the empty prompt.
        r = again("ENTER", r);
        assertThat(r.at("/fields/typeCode").asText()).isEmpty();
        assertThat(info(r)).isEqualTo(PROMPT);
    }

    @Test
    @Covers({"TS-COTRTUPC-08", "BR-COTRTUPC-08", "ERR-COTRTUPC-06", "Q-COTRTUPC-21"})
    void a_changed_description_is_validated_then_saved() {
        lookUp("01");
        // The protected type field is not taken from the screen.
        JsonNode r = send("ENTER", "02", "PURCHASES");
        assertThat(info(r).strip()).isEqualTo("Changes validated.Press F5 to save");
        assertThat(r.at("/fields/typeCode").asText()).isEqualTo("01");
        assertThat(r.at("/fields/description").asText()).isEqualTo("PURCHASES");
        assertThat(r.at("/focus").asText()).isEqualTo("description");
        assertThat(description(jdbc, "01")).isEqualTo("PURCHASE");
        r = send("PF5", "02", "SOMETHING ELSE");
        assertThat(info(r).strip()).isEqualTo("Changes committed to database");
        assertThat(description(jdbc, "01")).isEqualTo("PURCHASES");
        assertThat(description(jdbc, "02")).isEqualTo("PAYMENT");
    }

    @Test
    @Covers({"TS-COTRTUPC-09", "VR-COTRTUPC-07", "Q-COTRTUPC-11", "BR-COTRTUPC-13"})
    void a_case_only_change_is_no_change() {
        lookUp("01");
        JsonNode r = send("ENTER", "01", "Purchase");
        assertThat(msg(r)).isEqualTo("No change detected with respect to values fetched.");
        assertThat(r.at("/fields/description").asText()).isEqualTo("PURCHASE");
        assertThat(flag(r, "descriptionEditable")).isTrue();
        assertThat(keys(r)).isEqualTo("ENTER=Process F3=Exit F4 F12");
        assertThat(r.at("/focus").asText()).isEqualTo("description");
        assertThat(description(jdbc, "01")).isEqualTo("PURCHASE");
    }

    @Test
    @Covers({"TS-COTRTUPC-10", "VR-COTRTUPC-05", "BR-COTRTUPC-04"})
    void a_blank_description_is_required() {
        lookUp("01");
        JsonNode r = send("ENTER", "01", "   ");
        assertThat(msg(r)).isEqualTo("Transaction Desc must be supplied.");
        assertThat(r.at("/fields/description").asText()).isEqualTo("*");
        assertThat(flag(r, "descriptionInvalid")).isTrue();
        assertThat(flag(r, "descriptionEditable")).isTrue();
        assertThat(r.at("/focus").asText()).isEqualTo("description");
        assertThat(info(r)).isEqualTo(CHANGES);
        assertThat(keys(r)).isEqualTo("ENTER=Process F3=Exit ");
        // The star sent back unchanged is still no description.
        r = again("ENTER", r);
        assertThat(msg(r)).isEqualTo("Transaction Desc must be supplied.");
        // Correcting it validates the change (E -> N).
        r = send("ENTER", "01", "  RETAIL PURCHASE ");
        assertThat(info(r).strip()).isEqualTo("Changes validated.Press F5 to save");
        assertThat(r.at("/fields/description").asText()).isEqualTo("RETAIL PURCHASE");
    }

    @Test
    @Covers({"TS-COTRTUPC-11", "VR-COTRTUPC-06"})
    void the_description_allows_letters_digits_and_spaces_only() {
        lookUp("01");
        JsonNode r = send("ENTER", "01", "PURCHASE-RETAIL");
        assertThat(msg(r)).isEqualTo("Transaction Desc can have numbers or alphabets only.");
        assertThat(r.at("/fields/description").asText()).isEqualTo("PURCHASE-RETAIL");
        assertThat(flag(r, "descriptionInvalid")).isTrue();
        assertThat(info(r)).isEqualTo(CHANGES);
        assertThat(again("PF5", r).at("/message").asText()).isEqualTo("Invalid key pressed");
        assertThat(description(jdbc, "01")).isEqualTo("PURCHASE");
    }

    @Test
    @Covers({"TS-COTRTUPC-12", "BR-COTRTUPC-12", "BR-COTRTUPC-13", "NAV-COTRTUPC-05", "VR-COTRTUPC-07"})
    void pf12_cancels_a_validated_update() {
        lookUp("01");
        JsonNode r = send("ENTER", "01", "PURCHASES");
        assertThat(keys(r)).isEqualTo("ENTER=Process F3=Exit F5 F12");
        r = again("PF12", r);
        assertThat(msg(r)).isEqualTo("Update was cancelled");
        assertThat(r.at("/fields/description").asText()).isEqualTo("PURCHASE");
        assertThat(flag(r, "descriptionEditable")).isTrue();
        assertThat(info(r)).isEqualTo(CHANGES);
        assertThat(description(jdbc, "01")).isEqualTo("PURCHASE");
        // After the cancel, ENTER with the old values is no change and the state becomes E (no F4 any more).
        r = again("ENTER", r);
        assertThat(msg(r)).isEqualTo("No change detected with respect to values fetched.");
        assertThat(keys(r)).isEqualTo("ENTER=Process F3=Exit ");
    }

    @Test
    @Covers({"TS-COTRTUPC-13", "BR-COTRTUPC-14", "ERR-COTRTUPC-07", "NAV-COTRTUPC-03", "Q-COTRTUPC-06"})
    void deleting_a_type_with_categories_is_refused_and_the_next_key_continues() {
        lookUp("01");
        JsonNode r = send("PF4", "01", "PURCHASE");
        assertThat(info(r).strip()).isEqualTo("Delete this record ? Press F4 to confirm");
        assertThat(keys(r)).isEqualTo("F4 F12");
        assertThat(flag(r, "typeEditable")).isFalse();
        assertThat(flag(r, "descriptionEditable")).isFalse();
        assertThat(r.at("/fields/description").asText()).isEqualTo("PURCHASE");
        r = again("PF4", r);
        assertThat(msg(r)).startsWith("Please delete associated child records first:SQLCODE : -532:").hasSizeLessThanOrEqualTo(75);
        assertThat(info(r).strip()).isEqualTo("Changes unsuccessful");
        assertThat(flag(r, "typeInvalid")).isTrue();
        assertThat(keys(r)).isEqualTo("ENTER=Process F3=Exit ");
        assertThat(description(jdbc, "01")).isEqualTo("PURCHASE");
        // No abend: the next ENTER shows the empty prompt.
        r = again("ENTER", r);
        assertThat(r.at("/fields/abend").isNull()).isTrue();
        assertThat(info(r)).isEqualTo(PROMPT);
        assertThat(r.at("/fields/typeCode").asText()).isEmpty();
    }

    @Test
    @Covers({"TS-COTRTUPC-14", "BR-COTRTUPC-15", "BR-COTRTUPC-28", "BR-COTRTUPC-03", "Q-COTRTUPC-19"})
    void deleting_a_type_without_categories_clears_the_screen() {
        jdbc.update("INSERT INTO transaction_type (type_cd, description) VALUES ('15', 'INTEREST')");
        lookUp("15");
        JsonNode r = send("PF4", "15", "INTEREST");
        r = send("PF4", "99", "");
        assertThat(info(r).strip()).isEqualTo("Delete successful.");
        assertThat(r.has("message")).isFalse();
        assertThat(description(jdbc, "15")).isNull();
        assertThat(description(jdbc, "99")).isNull();
        assertThat(r.at("/fields/typeCode").asText()).isEmpty();
        assertThat(r.at("/fields/description").asText()).isEmpty();
        assertThat(flag(r, "typeEditable")).isTrue();
        // ENTER with nothing typed returns to the prompt; a typed type is looked up directly.
        JsonNode prompt = again("ENTER", r);
        assertThat(info(prompt)).isEqualTo(PROMPT);
        assertThat(prompt.has("message")).isFalse();
        jdbc.update("INSERT INTO transaction_type (type_cd, description) VALUES ('16', 'FEE')");
        lookUp("16");
        send("PF4", "16", "FEE");
        r = send("PF4", "16", "FEE");
        r = send("ENTER", "2", "");
        assertThat(r.at("/fields/typeCode").asText()).isEqualTo("02");
        assertThat(r.at("/fields/description").asText()).isEqualTo("PAYMENT");
    }

    @Test
    @Covers({"TS-COTRTUPC-15", "BR-COTRTUPC-12"})
    void pf12_cancels_a_delete() {
        lookUp("01");
        send("PF4", "01", "PURCHASE");
        JsonNode r = send("PF12", "01", "PURCHASE");
        assertThat(msg(r)).isEqualTo("Delete was cancelled");
        assertThat(r.at("/fields/typeCode").asText()).isEmpty();
        assertThat(flag(r, "typeEditable")).isTrue();
        assertThat(info(r)).isEqualTo(PROMPT);
        assertThat(description(jdbc, "01")).isEqualTo("PURCHASE");
    }

    @Test
    @Covers({"TS-COTRTUPC-16", "NAV-COTRTUPC-01", "Q-COTRTUPC-07"})
    void keys_not_valid_in_the_state_are_refused_and_the_screen_is_resent() {
        lookUp("01");
        JsonNode r = send("PF4", "01", "PURCHASE");
        for (String k : new String[] {"ENTER", "PF5", "PF7", "CLEAR", "PA1"}) {
            JsonNode again = send(k, "01", "PURCHASE");
            assertThat(again.at("/message").asText()).as(k).isEqualTo("Invalid key pressed");
            assertThat(info(again).strip()).isEqualTo("Delete this record ? Press F4 to confirm");
            assertThat(keys(again)).isEqualTo("F4 F12");
        }
        assertThat(description(jdbc, "01")).isEqualTo("PURCHASE");
        r = send("PF12", "", "");
        r = send("ENTER", "01", "");
        // Invalid in S: the row stays shown without receiving the screen.
        JsonNode refused = send("PF2", "01", "TYPED BUT NOT RECEIVED");
        assertThat(msg(refused)).isEqualTo("Invalid key pressed");
        assertThat(refused.at("/fields/description").asText()).isEqualTo("PURCHASE");
        assertThat(keys(refused)).isEqualTo("ENTER=Process F3=Exit F4 F12");
    }

    @Test
    @Covers({"NAV-COTRTUPC-07"})
    void pf13_to_pf24_act_as_pf1_to_pf12() {
        lookUp("01");
        JsonNode r = send("PF16", "01", "PURCHASE");
        assertThat(info(r).strip()).isEqualTo("Delete this record ? Press F4 to confirm");
        r = send("PF24", "01", "PURCHASE");
        assertThat(msg(r)).isEqualTo("Delete was cancelled");
        assertThat(send("PF15", "", "").at("/navigate").asText()).isEqualTo("COADM01C");
    }

    @Test
    @Covers({"TS-COTRTUPC-17", "NAV-COTRTUPC-02", "Q-COTRTUPC-10"})
    void pf3_returns_to_the_admin_menu_with_the_role_unchanged() {
        lookUp("01");
        JsonNode r = send("PF3", "01", "PURCHASE");
        assertThat(r.at("/navigate").asText()).isEqualTo("COADM01C");
        assertThat(r.has("fields")).isFalse();
        assertThat(client.show("COADM01C").has("navigate")).isFalse();
        // A regular user is refused and never becomes an administrator on PF3.
        ScreenClient user = new ScreenClient(mvc);
        user.signOn("USER0001");
        var res = user.showRaw(P);
        assertThat(res.getStatus()).isEqualTo(403);
        assertThat(user.body(res).at("/message").asText()).isEqualTo(CommonMessages.ADMIN_ONLY);
        assertThat(user.turn(P, "PF3", Map.of()).getStatus()).isEqualTo(403);
    }

    @Test
    @Covers({"TS-COTRTUPC-18", "NAV-COTRTUPC-02", "Q-COTRTUPC-05"})
    void pf3_after_pf2_on_the_list_returns_to_the_list() {
        JsonNode list = client.show("COTRTLIC");
        assertThat(client.post("COTRTLIC", "PF2", listInput(list)).at("/navigate").asText()).isEqualTo(P);
        client.show(P);
        send("ENTER", "01", "");
        assertThat(send("PF3", "", "").at("/navigate").asText()).isEqualTo("COTRTLIC");
        JsonNode back = client.show("COTRTLIC");
        assertThat(types(back)).startsWith("01", "02");
        assertThat(back.at("/fields/page").asText()).isEqualTo("1");
        // The caller is used once: entered from the admin menu next time, PF3 goes to the menu.
        client.show(P);
        assertThat(send("PF3", "", "").at("/navigate").asText()).isEqualTo("COADM01C");
    }

    @Test
    @Covers({"TS-COTRTUPC-19", "BR-COTRTUPC-06", "Q-COTRTUPC-01"})
    void on_the_not_found_screen_a_new_type_is_edited_before_the_lookup() {
        lookUp("15");
        JsonNode r = send("ENTER", "AB", "");
        assertThat(msg(r)).isEqualTo("Tran Type code must be numeric.");
        assertThat(keys(r)).isEqualTo("ENTER=Process F3=Exit ");
        assertThat(send("PF5", "AB", "").at("/message").asText()).isEqualTo("Invalid key pressed");
        lookUp("15");
        r = send("ENTER", "5", "");
        assertThat(r.at("/fields/typeCode").asText()).isEqualTo("05");
        assertThat(r.at("/fields/description").asText()).isEqualTo("REFUND");
        lookUp("15");
        r = send("ENTER", "16", "");
        assertThat(msg(r)).isEqualTo("No record found for this key in database");
        assertThat(r.at("/fields/typeCode").asText()).isEqualTo("16");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM transaction_type WHERE type_cd = 'AB'", Integer.class)).isZero();
    }

    @Test
    @Covers({"TS-COTRTUPC-22", "BR-COTRTUPC-01"})
    void a_turn_without_a_conversation_starts_with_the_empty_prompt() {
        ScreenClient anonymous = new ScreenClient(mvc);
        assertThat(anonymous.showRaw(P).getStatus()).isEqualTo(401);
        assertThat(anonymous.turn(P, "PF3", Map.of()).getStatus()).isEqualTo(401);
        // Signed on but no CTTU conversation yet: the screen is not received, the prompt is sent.
        JsonNode r = send("ENTER", "01", "SOMETHING");
        assertThat(r.at("/fields/typeCode").asText()).isEmpty();
        assertThat(info(r)).isEqualTo(PROMPT);
        assertThat(r.has("message")).isFalse();
        assertThat(send("PF3", "", "").at("/navigate").asText()).isEqualTo("COADM01C");
    }

    @Test
    @Covers({"TS-COTRTUPC-23", "ERR-COTRTUPC-08"})
    void deleting_a_type_another_user_already_deleted_reports_sqlcode_100() {
        lookUp("01");
        send("PF4", "01", "PURCHASE");
        jdbc.update("DELETE FROM transaction_category WHERE type_cd = '01'");
        jdbc.update("DELETE FROM transaction_type WHERE type_cd = '01'");
        JsonNode r = send("PF4", "01", "PURCHASE");
        assertThat(msg(r)).isEqualTo("Delete failed with message:SQLCODE :  100:");
        assertThat(info(r).strip()).isEqualTo("Changes unsuccessful");
        assertThat(flag(r, "typeInvalid")).isTrue();
        assertThat(r.at("/fields/typeCode").asText()).isEqualTo("01");
    }

    @Test
    @Covers({"Q-COTRTUPC-17"})
    void a_save_after_another_change_is_refused_and_never_reinserts() {
        lookUp("01");
        send("ENTER", "01", "PURCHASES");
        jdbc.update("UPDATE transaction_type SET description = 'OTHER USER' WHERE type_cd = '01'");
        JsonNode r = send("PF5", "01", "PURCHASES");
        assertThat(msg(r)).isEqualTo("Could not lock record for update");
        assertThat(info(r).strip()).isEqualTo("Changes unsuccessful");
        assertThat(description(jdbc, "01")).isEqualTo("OTHER USER");
        // The type field is open: ENTER re-reads the row as it is now (Q-COTRTUPC-19).
        r = again("ENTER", r);
        assertThat(r.at("/fields/description").asText()).isEqualTo("OTHER USER");
        assertThat(info(r)).isEqualTo(CHANGES);

        lookUp("02");
        send("ENTER", "02", "PAYMENTS");
        jdbc.update("DELETE FROM transaction_category WHERE type_cd = '02'");
        jdbc.update("DELETE FROM transaction_type WHERE type_cd = '02'");
        r = send("PF5", "02", "PAYMENTS");
        assertThat(msg(r)).isEqualTo("Could not lock record for update");
        assertThat(description(jdbc, "02")).isNull();
    }

    @Test
    @Covers({"Q-COTRTUPC-19"})
    void a_type_typed_after_a_cancelled_add_is_looked_up() {
        JsonNode r = again("PF5", lookUp("15"));
        r = send("ENTER", "15", "INTEREST");
        r = again("PF12", r);
        assertThat(msg(r)).isEqualTo("Update was cancelled");
        assertThat(info(r)).isEqualTo(PROMPT);
        assertThat(flag(r, "typeEditable")).isTrue();
        assertThat(r.at("/focus").asText()).isEqualTo("typeCode");
        r = send("ENTER", "04", "");
        assertThat(r.at("/fields/description").asText()).isEqualTo("AUTHORIZATION");
        assertThat(description(jdbc, "15")).isNull();
    }
}
