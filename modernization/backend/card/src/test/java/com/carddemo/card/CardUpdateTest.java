package com.carddemo.card;

import static org.assertj.core.api.Assertions.assertThat;

import com.carddemo.test.Covers;
import com.carddemo.test.ScreenClient;
import com.carddemo.test.TestApplication;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;

/** COCRDUPC credit card update: fetch, edits, confirmation, save, and concurrency. */
@SpringBootTest(classes = TestApplication.class)
@AutoConfigureMockMvc
class CardUpdateTest extends CardScreenTestSupport {

    static final String DETAILS = "Details of selected card shown above";
    static final String PROMPT = "Please enter Account and Card Number";
    static final String VALIDATED = "Changes validated.Press F5 to save";
    static final String COMMITTED = "Changes committed to database";
    static final String UNSUCCESSFUL = "Changes unsuccessful. Please try again";
    static final String NO_CHANGE = "No change detected with respect to values fetched.";
    static final String NAME_ALPHA = "Card name can only contain alphabets and spaces";
    static final String NOT_FOUND = "Did not find cards for this search condition";
    /** carddata.txt line 34: account 31, Lucious O'Connell. */
    static final String CARD34 = "7026637615032277";
    /** carddata.txt line 35: account 43, expiry 2025-08-29. */
    static final String CARD35 = "7058267261837752";

    /** One turn with the detail fields as given (null = as the screen showed them in {@code shown}). */
    JsonNode turn(ScreenClient c, String key, JsonNode shown, String name, String status, String month, String year) {
        Map<String, Object> f = new HashMap<>();
        f.put("accountId", shown.at("/fields/accountId").asText());
        f.put("cardNum", shown.at("/fields/cardNum").asText());
        f.put("name", name != null ? name : shown.at("/fields/name").asText());
        f.put("status", status != null ? status : shown.at("/fields/status").asText());
        f.put("expMonth", month != null ? month : shown.at("/fields/expMonth").asText());
        f.put("expYear", year != null ? year : shown.at("/fields/expYear").asText());
        return c.post("COCRDUPC", key, f);
    }

    JsonNode turn(String key, JsonNode shown, String name, String status, String month, String year) {
        return turn(admin, key, shown, name, status, month, year);
    }

    JsonNode keys(ScreenClient c, String key, String acct, String card) {
        Map<String, Object> f = new HashMap<>();
        f.put("accountId", acct);
        f.put("cardNum", card);
        return c.post("COCRDUPC", key, f);
    }

    JsonNode fromList(ScreenClient c, int row) {
        c.show("COCRDLIC");
        Map<String, Object> f = new HashMap<>();
        f.put("sel", sel(row, "U"));
        assertThat(c.post("COCRDLIC", "ENTER", f).at("/navigate").asText()).isEqualTo("COCRDUPC");
        return c.show("COCRDUPC");
    }

    JsonNode fetched(String acct, String card) {
        admin.show("COCRDUPC");
        JsonNode r = keys(admin, "ENTER", acct, card);
        assertThat(r.at("/fields/state").asText()).isEqualTo("S");
        return r;
    }

    int audits(String target) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE action = 'CARD_UPDATE' AND target = ?", Integer.class,
            target);
    }

    @Test
    @Covers({"TS-COCRDUPC-01", "NAV-COCRDUPC-04", "BR-COCRDUPC-02", "BR-COCRDUPC-15", "BR-COCRDUPC-14", "BR-COCRDUPC-16",
        "Q-COCRDUPC-15", "BR-COCRDUPC-12", "Q-COCRDUPC-11"})
    void from_the_list_the_card_is_fetched_for_editing() {
        JsonNode r = fromList(admin, 1);
        assertThat(r.at("/fields/accountId").asText()).isEqualTo(ACCT1);
        assertThat(r.at("/fields/cardNum").asText()).isEqualTo("************5740");
        assertThat(r.at("/fields/keysEditable").asBoolean()).isFalse();
        assertThat(r.at("/fields/detailsEditable").asBoolean()).isTrue();
        assertThat(r.at("/fields/name").asText()).isEqualTo("ANIYA VON");
        assertThat(r.at("/fields/status").asText()).isEqualTo("Y");
        assertThat(r.at("/fields/expMonth").asText()).isEqualTo("03");
        assertThat(r.at("/fields/expYear").asText()).isEqualTo("2023");
        assertThat(r.at("/fields/info").asText()).isEqualTo(DETAILS);
        assertThat(r.has("message")).isFalse();
        assertThat(r.at("/focus").asText()).isEqualTo("name");
        assertThat(r.at("/fields/confirmKeys").asBoolean()).isFalse();
        assertThat(r.toString()).doesNotContain(CARD1).doesNotContain("747");
    }

    @Test
    @Covers({"TS-COCRDUPC-02", "BR-COCRDUPC-03", "Q-COCRDUPC-07"})
    void enter_without_changes_reports_no_change() {
        JsonNode s = fromList(admin, 1);
        JsonNode r = turn("ENTER", s, null, null, null, null);
        assertThat(r.at("/message").asText()).isEqualTo(NO_CHANGE);
        assertThat(r.at("/fields/state").asText()).isEqualTo("S");
        assertThat(r.at("/fields/info").asText()).isEqualTo(DETAILS);
        assertThat(r.at("/focus").asText()).isEqualTo("name");
    }

    @Test
    @Covers({"TS-COCRDUPC-03", "BR-COCRDUPC-04", "BR-COCRDUPC-05", "Q-COCRDUPC-15"})
    void valid_changes_ask_for_confirmation_with_everything_protected() {
        Map<String, Object> before = cardRow(CARD1);
        JsonNode s = fromList(admin, 1);
        JsonNode n = turn("ENTER", s, null, "N", null, null);
        assertThat(n.at("/fields/state").asText()).isEqualTo("N");
        assertThat(n.at("/fields/info").asText()).isEqualTo(VALIDATED);
        assertThat(n.at("/fields/keysEditable").asBoolean()).isFalse();
        assertThat(n.at("/fields/detailsEditable").asBoolean()).isFalse();
        assertThat(n.at("/fields/confirmKeys").asBoolean()).isTrue();
        assertThat(n.at("/fields/status").asText()).isEqualTo("N");
        assertThat(n.has("message")).isFalse();
        assertThat(cardRow(CARD1)).isEqualTo(before);
    }

    @Test
    @Covers({"TS-COCRDUPC-04", "TS-COCRDUPC-05", "BR-COCRDUPC-07", "BR-COCRDUPC-08", "BR-COCRDUPC-13", "BR-COCRDUPC-17",
        "NAV-COCRDUPC-03", "BR-COCRDUPC-09", "Q-COCRDUPC-01", "Q-COCRDUPC-03", "Q-COCRDUPC-04", "Q-COCRDUPC-17"})
    void pf5_saves_only_name_status_and_expiry_and_audits_then_returns_to_the_list() {
        JsonNode s = fromList(admin, 1);
        JsonNode n = turn("ENTER", s, null, "N", null, null);
        JsonNode c = turn("PF5", n, null, null, null, null);
        assertThat(c.at("/fields/state").asText()).isEqualTo("C");
        assertThat(c.at("/fields/info").asText()).isEqualTo(COMMITTED);
        assertThat(c.has("message")).isFalse();
        assertThat(c.at("/fields/detailsEditable").asBoolean()).isFalse();
        assertThat(c.at("/fields/keysEditable").asBoolean()).isFalse();
        assertThat(column(CARD1, "account_id")).isEqualTo("50");
        assertThat(column(CARD1, "embossed_name")).isEqualTo("ANIYA VON");
        assertThat(expiry(CARD1)).isEqualTo(LocalDate.of(2023, 3, 9));
        assertThat(column(CARD1, "active_status")).isEqualTo("N");
        assertThat(column(CARD1, "cvv_cd")).isEqualTo("747");
        assertThat(audits("************5740")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT fields FROM audit_log WHERE action = 'CARD_UPDATE'", String.class))
            .isEqualTo("embossedName,activeStatus");
        assertThat(jdbc.queryForObject("SELECT actor FROM audit_log WHERE action = 'CARD_UPDATE'", String.class))
            .isEqualTo("ADMIN001");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE target LIKE '%" + CARD1 + "%'", Integer.class))
            .isZero();
        JsonNode next = turn("ENTER", c, null, null, null, null);
        assertThat(next.at("/navigate").asText()).isEqualTo("COCRDLIC");
        JsonNode list = admin.show("COCRDLIC");
        assertThat(list.at("/fields/pageNum").asText()).isEqualTo("1");
        assertThat(list.at("/fields/rows/0/status").asText()).isEqualTo("N");
    }

    @Test
    @Covers({"TS-COCRDUPC-06", "BR-COCRDUPC-05", "NAV-COCRDUPC-01"})
    void enter_in_confirmation_and_pf5_before_it_never_write() {
        Map<String, Object> before = cardRow(CARD1);
        JsonNode s = fromList(admin, 1);
        JsonNode n = turn("ENTER", s, null, "N", null, null);
        JsonNode again = turn("ENTER", n, null, "Y", null, null);
        assertThat(again.at("/fields/state").asText()).isEqualTo("N");
        assertThat(again.at("/fields/status").asText()).isEqualTo("N");
        assertThat(again.at("/fields/info").asText()).isEqualTo(VALIDATED);
        assertThat(cardRow(CARD1)).isEqualTo(before);
        JsonNode s2 = turn("PF12", again, null, null, null, null);
        JsonNode early = turn("PF5", s2, null, "N", null, null);
        assertThat(early.at("/fields/state").asText()).isEqualTo("N");
        assertThat(cardRow(CARD1)).isEqualTo(before);
        JsonNode folded = turn("PF17", early, null, null, null, null);
        assertThat(folded.at("/fields/state").asText()).isEqualTo("C");
    }

    @Test
    @Covers({"TS-COCRDUPC-07", "BR-COCRDUPC-10", "Q-COCRDUPC-15"})
    void pf12_rereads_the_stored_values() {
        JsonNode s = fromList(admin, 1);
        JsonNode n = turn("ENTER", s, null, "N", null, null);
        JsonNode r = turn("PF12", n, null, null, null, null);
        assertThat(r.at("/fields/state").asText()).isEqualTo("S");
        assertThat(r.at("/fields/name").asText()).isEqualTo("ANIYA VON");
        assertThat(r.at("/fields/status").asText()).isEqualTo("Y");
        assertThat(r.at("/fields/expMonth").asText()).isEqualTo("03");
        assertThat(r.at("/fields/expYear").asText()).isEqualTo("2023");
        assertThat(r.at("/fields/info").asText()).isEqualTo(DETAILS);
        JsonNode e = turn("ENTER", r, "123", null, null, null);
        assertThat(e.at("/fields/state").asText()).isEqualTo("E");
        JsonNode back = turn("PF12", e, null, null, null, null);
        assertThat(back.at("/fields/state").asText()).isEqualTo("S");
        assertThat(back.has("message")).isFalse();
    }

    @Test
    @Covers({"TS-COCRDUPC-08", "VR-COCRDUPC-08"})
    void a_name_with_a_digit_is_rejected() {
        JsonNode s = fromList(admin, 1);
        JsonNode r = turn("ENTER", s, "Aniya Von2", null, null, null);
        assertThat(r.at("/message").asText()).isEqualTo(NAME_ALPHA);
        assertThat(r.at("/fields/errors").toString()).isEqualTo("[\"name\"]");
        assertThat(r.at("/focus").asText()).isEqualTo("name");
        assertThat(r.at("/fields/info").asText()).isEqualTo("Update card details presented above.");
        assertThat(r.at("/fields/state").asText()).isEqualTo("E");
        assertThat(r.at("/fields/name").asText()).isEqualTo("Aniya Von2");
        assertThat(r.at("/fields/detailsEditable").asBoolean()).isTrue();
    }

    @Test
    @Covers({"TS-COCRDUPC-09", "VR-COCRDUPC-07"})
    void an_erased_name_is_not_provided() {
        JsonNode s = fromList(admin, 1);
        JsonNode r = turn("ENTER", s, "", null, null, null);
        assertThat(r.at("/message").asText()).isEqualTo("Card name not provided");
        assertThat(r.at("/fields/name").asText()).isEqualTo("*");
        assertThat(r.at("/fields/errors").toString()).isEqualTo("[\"name\"]");
        assertThat(turn("ENTER", r, "000", null, null, null).at("/message").asText()).isEqualTo("Card name not provided");
    }

    @Test
    @Covers({"TS-COCRDUPC-10", "VR-COCRDUPC-09"})
    void the_status_must_be_upper_case_y_or_n() {
        JsonNode s = fromList(admin, 1);
        JsonNode same = turn("ENTER", s, null, "y", null, null);
        assertThat(same.at("/message").asText()).isEqualTo(NO_CHANGE);
        JsonNode lower = turn("ENTER", s, null, "y", "04", null);
        assertThat(lower.at("/message").asText()).isEqualTo("Card Active Status must be Y or N");
        assertThat(lower.at("/fields/status").asText()).isEqualTo("y");
        assertThat(lower.at("/fields/errors").toString()).isEqualTo("[\"status\"]");
        assertThat(lower.at("/focus").asText()).isEqualTo("status");
        JsonNode erased = turn("ENTER", lower, null, "", null, null);
        assertThat(erased.at("/message").asText()).isEqualTo("Card Active Status must be Y or N");
        assertThat(erased.at("/fields/status").asText()).isEqualTo("*");
        assertThat(turn("ENTER", erased, null, "X", null, null).at("/fields/status").asText()).isEqualTo("X");
    }

    @Test
    @Covers({"TS-COCRDUPC-11", "VR-COCRDUPC-10", "Q-COCRDUPC-19"})
    void the_month_must_be_1_to_12() {
        JsonNode s = fromList(admin, 1);
        JsonNode r = turn("ENTER", s, null, null, "13", null);
        assertThat(r.at("/message").asText()).isEqualTo("Card expiry month must be between 1 and 12");
        assertThat(r.at("/fields/expMonth").asText()).isEqualTo("13");
        assertThat(r.at("/focus").asText()).isEqualTo("expMonth");
        JsonNode zero = turn("ENTER", r, null, null, "00", null);
        assertThat(zero.at("/message").asText()).isEqualTo("Card expiry month must be between 1 and 12");
        assertThat(zero.at("/fields/expMonth").asText()).isEqualTo("*");
        assertThat(turn("ENTER", zero, null, null, "AB", null).at("/fields/expMonth").asText()).isEqualTo("AB");
        JsonNode one = turn("ENTER", zero, null, null, "5", null);
        assertThat(one.at("/fields/state").asText()).isEqualTo("N");
        turn("PF5", one, null, null, null, null);
        assertThat(expiry(CARD1)).isEqualTo(LocalDate.of(2023, 5, 9));
    }

    @Test
    @Covers({"TS-COCRDUPC-12", "VR-COCRDUPC-11", "VR-COCRDUPC-13"})
    void the_year_must_be_1950_to_2099() {
        JsonNode s = fromList(admin, 1);
        for (String bad : List.of("1949", "2100", "20X5", "202")) {
            JsonNode r = turn("ENTER", s, null, null, null, bad);
            assertThat(r.at("/message").asText()).isEqualTo("Invalid card expiry year");
            assertThat(r.at("/fields/errors").toString()).isEqualTo("[\"expYear\"]");
        }
        JsonNode zero = turn("ENTER", s, null, null, null, "0000");
        assertThat(zero.at("/fields/expYear").asText()).isEqualTo("*");
        JsonNode low = turn("ENTER", zero, null, null, null, "1950");
        assertThat(low.at("/fields/state").asText()).isEqualTo("N");
        JsonNode s2 = turn("PF12", low, null, null, null, null);
        assertThat(turn("ENTER", s2, null, null, null, "2099").at("/fields/state").asText()).isEqualTo("N");
    }

    @Test
    @Covers({"TS-COCRDUPC-13", "VR-COCRDUPC-12", "BR-COCRDUPC-16"})
    void only_the_first_message_is_kept_but_every_field_is_flagged() {
        JsonNode s = fromList(admin, 1);
        JsonNode r = turn("ENTER", s, "123", "X", "13", "1900");
        assertThat(r.at("/message").asText()).isEqualTo(NAME_ALPHA);
        assertThat(r.at("/fields/errors").toString()).isEqualTo("[\"name\",\"status\",\"expMonth\",\"expYear\"]");
        assertThat(r.at("/focus").asText()).isEqualTo("name");
    }

    @Test
    @Covers({"TS-COCRDUPC-14", "BR-COCRDUPC-06", "ERR-COCRDUPC-04", "Q-COCRDUPC-13"})
    void a_record_changed_meanwhile_is_not_overwritten() {
        JsonNode s = fromList(admin, 1);
        JsonNode n = turn("ENTER", s, null, "N", null, null);
        jdbc.update("UPDATE card SET embossed_name = 'SOMEONE ELSE' WHERE card_num = ?", CARD1);
        JsonNode r = turn("PF5", n, null, null, null, null);
        assertThat(r.at("/message").asText()).isEqualTo("Record changed by some one else. Please review");
        assertThat(r.at("/fields/state").asText()).isEqualTo("S");
        assertThat(r.at("/fields/name").asText()).isEqualTo("SOMEONE ELSE");
        assertThat(r.at("/fields/status").asText()).isEqualTo("Y");
        assertThat(r.at("/fields/info").asText()).isEqualTo(DETAILS);
        assertThat(column(CARD1, "active_status")).isEqualTo("Y");
        assertThat(audits("************5740")).isZero();
        JsonNode retry = turn("ENTER", r, null, "N", null, null);
        assertThat(turn("PF5", retry, null, null, null, null).at("/fields/state").asText()).isEqualTo("C");
        assertThat(column(CARD1, "embossed_name")).isEqualTo("SOMEONE ELSE");
        assertThat(column(CARD1, "active_status")).isEqualTo("N");
    }

    @Test
    @Covers({"BR-COCRDUPC-06"})
    void the_account_is_not_part_of_the_concurrency_check_but_the_cvv_is() {
        JsonNode s = fromList(admin, 1);
        JsonNode n = turn("ENTER", s, null, "N", null, null);
        jdbc.update("UPDATE card SET cvv_cd = '111' WHERE card_num = ?", CARD1);
        assertThat(turn("PF5", n, null, null, null, null).at("/message").asText())
            .isEqualTo("Record changed by some one else. Please review");
        JsonNode s2 = fromList(admin, 1);
        JsonNode n2 = turn("ENTER", s2, null, "N", null, null);
        jdbc.update("UPDATE card SET embossed_name = 'aniya von' WHERE card_num = ?", CARD1);
        assertThat(turn("PF5", n2, null, null, null, null).at("/fields/state").asText()).isEqualTo("C");
    }

    @Test
    @Covers({"TS-COCRDUPC-15", "ERR-COCRDUPC-02", "BR-COCRDUPC-09", "BR-COCRDUPC-15"})
    void a_deleted_record_cannot_be_locked() {
        JsonNode s = fromList(admin, 1);
        JsonNode n = turn("ENTER", s, null, "N", null, null);
        jdbc.update("DELETE FROM card WHERE card_num = ?", CARD1);
        JsonNode r = turn("PF5", n, null, null, null, null);
        assertThat(r.at("/message").asText()).isEqualTo("Could not lock record for update");
        assertThat(r.at("/fields/state").asText()).isEqualTo("L");
        assertThat(r.at("/fields/info").asText()).isEqualTo(UNSUCCESSFUL);
        assertThat(r.at("/fields/keysEditable").asBoolean()).isTrue();
        assertThat(r.at("/fields/detailsEditable").asBoolean()).isFalse();
        assertThat(turn("ENTER", r, null, null, null, null).at("/navigate").asText()).isEqualTo("COCRDLIC");

        JsonNode m = fetched(ACCT2, CARD2);
        JsonNode mn = turn("ENTER", m, null, "N", null, null);
        jdbc.update("DELETE FROM card WHERE card_num = ?", CARD2);
        JsonNode ml = turn("PF5", mn, null, null, null, null);
        assertThat(ml.at("/fields/state").asText()).isEqualTo("L");
        JsonNode search = turn("ENTER", ml, null, null, null, null);
        assertThat(search.has("navigate")).isFalse();
        assertThat(search.at("/fields/info").asText()).isEqualTo(PROMPT);
        assertThat(search.at("/fields/accountId").asText()).isEmpty();
        assertThat(search.at("/fields/state").asText()).isEmpty();
    }

    @Test
    @Covers({"TS-COCRDUPC-16", "NAV-COCRDUPC-05", "VR-COCRDUPC-05", "ERR-COCRDUPC-07", "NAV-COCRDUPC-06"})
    void the_menu_entry_shows_the_search_screen() {
        JsonNode r = admin.show("COCRDUPC");
        assertThat(r.at("/fields/info").asText()).isEqualTo(PROMPT);
        assertThat(r.at("/fields/keysEditable").asBoolean()).isTrue();
        assertThat(r.at("/fields/detailsEditable").asBoolean()).isFalse();
        assertThat(r.at("/fields/name").asText()).isEmpty();
        assertThat(r.at("/focus").asText()).isEqualTo("accountId");
        JsonNode empty = keys(admin, "ENTER", "", "");
        assertThat(empty.has("navigate")).isFalse();
        assertThat(empty.at("/message").asText()).isEqualTo("No input received");
        assertThat(empty.at("/fields/accountId").asText()).isEqualTo("*");
        assertThat(empty.at("/fields/cardNum").asText()).isEqualTo("*");
        assertThat(empty.at("/fields/errors").toString()).isEqualTo("[\"accountId\",\"cardNum\"]");
        assertThat(admin.post("COCRDUPC", "PF12", Map.of()).at("/message").asText()).isEqualTo("No input received");
    }

    @Test
    @Covers({"TS-COCRDUPC-17", "VR-COCRDUPC-01", "VR-COCRDUPC-02", "VR-COCRDUPC-03", "VR-COCRDUPC-04", "Q-COCRDUPC-18",
        "BR-COCRDUPC-16"})
    void the_search_keys_are_edited() {
        admin.show("COCRDUPC");
        JsonNode a = keys(admin, "ENTER", "", CARD2);
        assertThat(a.at("/message").asText()).isEqualTo("Account number not provided");
        assertThat(a.at("/fields/accountId").asText()).isEqualTo("*");
        JsonNode c = keys(admin, "ENTER", ACCT2, "");
        assertThat(c.at("/message").asText()).isEqualTo("Card number not provided");
        assertThat(c.at("/focus").asText()).isEqualTo("cardNum");
        JsonNode shortAcct = keys(admin, "ENTER", "27", CARD2);
        assertThat(shortAcct.at("/message").asText()).isEqualTo("ACCOUNT FILTER,IF SUPPLIED MUST BE A 11 DIGIT NUMBER");
        assertThat(shortAcct.at("/fields/accountId").asText()).as("raw input echoed").isEqualTo("27");
        assertThat(shortAcct.at("/fields/errors").toString()).isEqualTo("[\"accountId\"]");
        assertThat(shortAcct.at("/focus").asText()).isEqualTo("accountId");
        JsonNode alpha = keys(admin, "ENTER", "0000000002A", CARD2);
        assertThat(alpha.at("/message").asText()).isEqualTo("ACCOUNT FILTER,IF SUPPLIED MUST BE A 11 DIGIT NUMBER");
        assertThat(alpha.at("/fields/accountId").asText()).isEqualTo("0000000002A");
        assertThat(alpha.at("/fields/state").asText()).isEmpty();
        assertThat(alpha.at("/fields/keysEditable").asBoolean()).isTrue();
        JsonNode badCard = keys(admin, "ENTER", ACCT2, "0683X");
        assertThat(badCard.at("/message").asText()).isEqualTo("CARD ID FILTER,IF SUPPLIED MUST BE A 16 DIGIT NUMBER");
        assertThat(badCard.at("/fields/errors").toString()).isEqualTo("[\"cardNum\"]");
        assertThat(badCard.at("/fields/state").asText()).isEmpty();
    }

    @Test
    @Covers({"TS-COCRDUPC-18", "VR-COCRDUPC-06"})
    void an_unknown_card_stays_not_fetched() {
        admin.show("COCRDUPC");
        JsonNode r = keys(admin, "ENTER", ACCT2, "0000000000000001");
        assertThat(r.at("/message").asText()).isEqualTo(NOT_FOUND);
        assertThat(r.at("/fields/errors").toString()).isEqualTo("[\"accountId\",\"cardNum\"]");
        assertThat(r.at("/fields/state").asText()).isEmpty();
        assertThat(r.at("/fields/keysEditable").asBoolean()).isTrue();
        assertThat(r.at("/focus").asText()).isEqualTo("accountId");
    }

    @Test
    @Covers({"TS-COCRDUPC-19", "BR-COCRDUPC-01", "BR-COCRDUPC-02"})
    void typed_keys_fetch_the_card_upper_cased() {
        JsonNode r = fetched(ACCT2, CARD2);
        assertThat(r.at("/fields/name").asText()).isEqualTo("WARD JONES");
        assertThat(r.at("/fields/status").asText()).isEqualTo("Y");
        assertThat(r.at("/fields/expMonth").asText()).isEqualTo("07");
        assertThat(r.at("/fields/expYear").asText()).isEqualTo("2025");
        assertThat(r.at("/fields/cardNum").asText()).isEqualTo("************1516");
        assertThat(r.at("/fields/keysEditable").asBoolean()).isFalse();
        assertThat(r.toString()).doesNotContain(CARD2);
    }

    @Test
    @Covers({"TS-COCRDUPC-20", "Q-COCRDUPC-02", "BR-COCRDUPC-01"})
    void a_card_of_another_account_is_not_found_and_never_reparented() {
        admin.show("COCRDUPC");
        JsonNode r = keys(admin, "ENTER", "00000000001", CARD2);
        assertThat(r.at("/message").asText()).isEqualTo(NOT_FOUND);
        assertThat(r.at("/fields/state").asText()).isEmpty();
        assertThat(column(CARD2, "account_id")).isEqualTo("27");
        assertThat(jdbc.queryForObject("SELECT account_id FROM card_xref WHERE card_num = ?", Long.class, CARD2)).isEqualTo(27L);
    }

    @Test
    @Covers({"TS-COCRDUPC-21", "Q-COCRDUPC-05", "VR-COCRDUPC-08"})
    void a_name_with_an_apostrophe_cannot_be_saved() {
        JsonNode s = fetched("00000000031", CARD34);
        assertThat(s.at("/fields/name").asText()).isEqualTo("LUCIOUS O'CONNELL");
        JsonNode r = turn("ENTER", s, null, "N", null, null);
        assertThat(r.at("/message").asText()).isEqualTo(NAME_ALPHA);
        assertThat(r.at("/fields/state").asText()).isEqualTo("E");
    }

    @Test
    @Covers({"TS-COCRDUPC-22", "Q-COCRDUPC-06", "BR-COCRDUPC-11", "VR-COCRDUPC-13"})
    void a_kept_day_past_the_new_month_end_becomes_the_last_day() {
        JsonNode s = fetched("00000000043", CARD35);
        assertThat(s.toString()).doesNotContain("\"29\"");
        JsonNode n = turn("ENTER", s, null, null, "02", null);
        assertThat(turn("PF5", n, null, null, null, null).at("/fields/state").asText()).isEqualTo("C");
        assertThat(expiry(CARD35)).isEqualTo(LocalDate.of(2025, 2, 28));
        jdbc.update("UPDATE card SET expiration_date = '2025-08-29' WHERE card_num = ?", CARD35);
        JsonNode s2 = fetched("00000000043", CARD35);
        JsonNode n2 = turn("ENTER", s2, null, null, "02", "2028");
        turn("PF5", n2, null, null, null, null);
        assertThat(expiry(CARD35)).isEqualTo(LocalDate.of(2028, 2, 29));
        JsonNode s3 = fetched("00000000043", CARD35);
        JsonNode n3 = turn("ENTER", s3, null, null, "03", null);
        turn("PF5", n3, null, null, null, null);
        assertThat(expiry(CARD35)).isEqualTo(LocalDate.of(2028, 3, 29));
    }

    @Test
    @Covers({"TS-COCRDUPC-23", "BR-COCRDUPC-03", "Q-COCRDUPC-04", "Q-COCRDUPC-07"})
    void case_only_name_changes_are_no_change_but_a_typed_name_is_saved_as_typed() {
        JsonNode s = fromList(admin, 1);
        assertThat(turn("ENTER", s, "aniya von", null, null, null).at("/message").asText()).isEqualTo(NO_CHANGE);
        JsonNode n = turn("ENTER", s, "Aniya Von", "N", null, null);
        assertThat(n.at("/fields/name").asText()).isEqualTo("Aniya Von");
        turn("PF5", n, null, null, null, null);
        assertThat(column(CARD1, "embossed_name")).isEqualTo("Aniya Von");
        assertThat(jdbc.queryForObject("SELECT fields FROM audit_log WHERE action = 'CARD_UPDATE'", String.class))
            .isEqualTo("activeStatus");
    }

    @Test
    @Covers({"TS-COCRDUPC-24", "BR-COCRDUPC-09", "Q-COCRDUPC-13"})
    void after_a_save_on_the_menu_path_the_next_key_shows_an_empty_search() {
        JsonNode s = fetched(ACCT2, CARD2);
        JsonNode n = turn("ENTER", s, null, "N", null, null);
        JsonNode c = turn("PF5", n, null, null, null, null);
        assertThat(c.at("/fields/info").asText()).isEqualTo(COMMITTED);
        JsonNode r = turn("ENTER", c, null, null, null, null);
        assertThat(r.has("navigate")).isFalse();
        assertThat(r.at("/fields/info").asText()).isEqualTo(PROMPT);
        assertThat(r.at("/fields/accountId").asText()).isEmpty();
        assertThat(r.at("/fields/cardNum").asText()).isEmpty();
        assertThat(r.at("/fields/name").asText()).isEmpty();
        assertThat(r.has("message")).isFalse();
        assertThat(r.at("/fields/keysEditable").asBoolean()).isTrue();
        assertThat(keys(admin, "ENTER", ACCT2, "************1516").at("/message").asText())
            .isEqualTo("CARD ID FILTER,IF SUPPLIED MUST BE A 16 DIGIT NUMBER");
    }

    @Test
    @Covers({"TS-COCRDUPC-25", "NAV-COCRDUPC-02", "BR-COCRDUPC-13", "BR-COCRDUPC-17", "Q-COCRDUPC-10", "Q-COCRDUPC-12"})
    void pf3_discards_changes_and_returns_to_the_caller() {
        Map<String, Object> before = cardRow(CARD1);
        JsonNode s = fromList(admin, 1);
        JsonNode e = turn("ENTER", s, "123", null, null, null);
        assertThat(e.at("/fields/state").asText()).isEqualTo("E");
        assertThat(turn("PF3", e, null, null, null, null).at("/navigate").asText()).isEqualTo("COCRDLIC");
        assertThat(cardRow(CARD1)).isEqualTo(before);
        JsonNode m = fetched(ACCT2, CARD2);
        JsonNode n = turn("ENTER", m, null, "N", null, null);
        assertThat(turn("PF3", n, null, null, null, null).at("/navigate").asText()).isEqualTo("COMEN01C");
        assertThat(column(CARD2, "active_status")).isEqualTo("Y");
        assertThat(admin.show("COADM01C").has("fields")).isTrue();
        JsonNode fresh = admin.show("COCRDUPC");
        assertThat(fresh.at("/fields/state").asText()).isEmpty();
    }

    @Test
    @Covers({"TS-COCRDUPC-26", "Q-COCRDUPC-08", "BR-COCRDUPC-10"})
    void pf12_on_the_menu_path_ignores_the_discarded_input() {
        JsonNode s = fetched(ACCT2, CARD2);
        JsonNode r = turn("PF12", s, "123", null, null, null);
        assertThat(r.at("/fields/state").asText()).isEqualTo("S");
        assertThat(r.at("/fields/name").asText()).isEqualTo("WARD JONES");
        assertThat(r.has("message")).isFalse();
        assertThat(r.at("/fields/errors").size()).isZero();
    }

    @Test
    @Covers({"TS-COCRDUPC-27", "Q-COCRDUPC-20", "VR-COCRDUPC-06", "BR-COCRDUPC-10"})
    void a_failed_pf12_reread_on_the_menu_path_returns_to_the_search_screen() {
        JsonNode s = fetched(ACCT2, CARD2);
        JsonNode n = turn("ENTER", s, null, "N", null, null);
        assertThat(n.at("/fields/state").asText()).isEqualTo("N");
        jdbc.update("DELETE FROM card WHERE card_num = ?", CARD2);
        JsonNode r = turn("PF12", n, null, null, null, null);
        assertThat(r.at("/message").asText()).isEqualTo(NOT_FOUND);
        assertThat(r.at("/fields/errors").toString()).isEqualTo("[\"accountId\",\"cardNum\"]");
        assertThat(r.at("/focus").asText()).isEqualTo("accountId");
        assertThat(r.at("/fields/state").asText()).as("not fetched, not S").isEmpty();
        assertThat(r.at("/fields/info").asText()).isEqualTo(PROMPT);
        assertThat(r.at("/fields/accountId").asText()).isEqualTo(ACCT2);
        assertThat(r.at("/fields/cardNum").asText()).isEqualTo("************1516");
        assertThat(r.at("/fields/keysEditable").asBoolean()).isTrue();
        assertThat(r.at("/fields/detailsEditable").asBoolean()).isFalse();
        assertThat(r.at("/fields/confirmKeys").asBoolean()).isFalse();
        for (String f : List.of("name", "status", "expMonth", "expYear")) {
            assertThat(r.at("/fields/" + f).asText()).as(f).isEmpty();
        }
        assertThat(r.toString()).doesNotContain(CARD2);

        JsonNode pf5 = turn("PF5", r, "WARD JONES", "N", "07", "2025");
        assertThat(pf5.at("/fields/state").asText()).as("no save is possible").isEmpty();
        assertThat(pf5.at("/message").asText()).isEqualTo(NOT_FOUND);
        assertThat(audits("************1516")).isZero();
        JsonNode other = keys(admin, "ENTER", ACCT1, CARD1);
        assertThat(other.at("/fields/state").asText()).as("the keys stay usable").isEqualTo("S");
    }

    @Test
    @Covers({"Q-COCRDUPC-09"})
    void a_card_deleted_since_the_list_gives_a_read_only_not_found_screen() {
        admin.show("COCRDLIC");
        jdbc.update("DELETE FROM card WHERE card_num = ?", CARD1);
        Map<String, Object> f = new HashMap<>();
        f.put("sel", sel(1, "U"));
        admin.post("COCRDLIC", "ENTER", f);
        JsonNode r = admin.show("COCRDUPC");
        assertThat(r.at("/message").asText()).isEqualTo(NOT_FOUND);
        assertThat(r.at("/fields/state").asText()).isEmpty();
        assertThat(r.at("/fields/detailsEditable").asBoolean()).isFalse();
        assertThat(r.at("/fields/keysEditable").asBoolean()).isFalse();
        assertThat(r.at("/fields/cardNum").asText()).isEqualTo("************5740");
        assertThat(r.at("/fields/name").asText()).isEmpty();
        JsonNode pf5 = turn("PF5", r, "X", "N", "01", "2030");
        assertThat(pf5.at("/fields/state").asText()).isEmpty();
        assertThat(pf5.at("/message").asText()).isEqualTo(NOT_FOUND);
        assertThat(turn("PF3", pf5, null, null, null, null).at("/navigate").asText()).isEqualTo("COCRDLIC");
    }

    @Test
    @Covers({"Q-COCRDUPC-17"})
    void a_regular_user_can_update_only_cards_of_linked_accounts() {
        ScreenClient other = signedOn(nonOwnerOf(27));
        other.show("COCRDUPC");
        JsonNode r = keys(other, "ENTER", ACCT2, CARD2);
        assertThat(r.at("/message").asText()).isEqualTo(NOT_FOUND);
        assertThat(r.at("/fields/state").asText()).isEmpty();
        String owner = ownerOf(27);
        ScreenClient mine = signedOn(owner);
        mine.show("COCRDUPC");
        JsonNode s = keys(mine, "ENTER", ACCT2, CARD2);
        JsonNode n = turn(mine, "ENTER", s, null, "N", null, null);
        assertThat(turn(mine, "PF5", n, null, null, null, null).at("/fields/state").asText()).isEqualTo("C");
        assertThat(jdbc.queryForObject("SELECT actor FROM audit_log WHERE action = 'CARD_UPDATE' AND target = ?", String.class,
            "************1516")).isEqualTo(owner);
    }
}
