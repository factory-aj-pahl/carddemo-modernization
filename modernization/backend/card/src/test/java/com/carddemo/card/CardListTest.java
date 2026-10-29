package com.carddemo.card;

import static org.assertj.core.api.Assertions.assertThat;

import com.carddemo.test.Covers;
import com.carddemo.test.ScreenClient;
import com.carddemo.test.TestApplication;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;

/** COCRDLIC credit card list against the 50 sample cards (one per account, all active). */
@SpringBootTest(classes = TestApplication.class)
@AutoConfigureMockMvc
class CardListTest extends CardScreenTestSupport {

    static final String INFO = "TYPE S FOR DETAIL, U TO UPDATE ANY RECORD";
    static final String NO_MORE_RECORDS = "NO MORE RECORDS TO SHOW";
    static final String NO_RECORDS_FOUND = "NO RECORDS FOUND FOR THIS SEARCH CONDITION.";

    JsonNode list(ScreenClient c, String key, String acct, String card, List<String> sel) {
        Map<String, Object> f = new HashMap<>();
        f.put("accountFilter", acct);
        f.put("cardFilter", card);
        f.put("sel", sel);
        return c.post("COCRDLIC", key, f);
    }

    JsonNode list(String key, String acct, String card, List<String> sel) {
        return list(admin, key, acct, card, sel);
    }

    JsonNode list(String key) {
        return list(key, "", "", sel());
    }

    List<String> page(int n) {
        List<String> all = allCards(null);
        return maskedAll(all.subList((n - 1) * 7, Math.min(all.size(), n * 7)));
    }

    JsonNode toPage(int n) {
        JsonNode r = admin.show("COCRDLIC");
        for (int i = 1; i < n; i++) {
            r = list("PF8");
        }
        return r;
    }

    @Test
    @Covers({"TS-COCRDLIC-01", "BR-COCRDLIC-02", "BR-COCRDLIC-07", "NAV-COCRDLIC-09", "BR-COCRDLIC-13", "BR-COCRDLIC-16",
        "Q-COCRDLIC-16", "Q-COCRDLIC-05"})
    void first_entry_shows_page_one_masked_in_card_number_order() {
        Map<String, Object> before = cardRow(CARD1);
        JsonNode r = admin.show("COCRDLIC");
        assertThat(r.at("/fields/pageNum").asText()).isEqualTo("1");
        assertThat(r.at("/fields/rows/0/accountId").asText()).isEqualTo("00000000050");
        assertThat(r.at("/fields/rows/0/cardNum").asText()).isEqualTo("************5740");
        assertThat(r.at("/fields/rows/0/status").asText()).isEqualTo("Y");
        assertThat(r.at("/fields/rows/1/accountId").asText()).isEqualTo("00000000027");
        assertThat(r.at("/fields/rows/6/accountId").asText()).isEqualTo("00000000037");
        assertThat(r.at("/fields/rows/6/cardNum").asText()).isEqualTo("************8931");
        assertThat(cards(r)).containsExactlyElementsOf(page(1));
        assertThat(r.at("/fields/info").asText()).isEqualTo(INFO);
        assertThat(r.has("message")).isFalse();
        assertThat(r.at("/focus").asText()).isEqualTo("accountFilter");
        assertThat(r.at("/fields/accountFilter").asText()).isEmpty();
        assertThat(r.toString()).doesNotContain(CARD1).doesNotContain(CARD2);
        assertThat(cardRow(CARD1)).isEqualTo(before);
    }

    @Test
    @Covers({"TS-COCRDLIC-02", "BR-COCRDLIC-04", "BR-COCRDLIC-05", "NAV-COCRDLIC-04", "BR-COCRDLIC-08", "NAV-COCRDLIC-08"})
    void pf8_shows_the_next_seven_cards() {
        admin.show("COCRDLIC");
        JsonNode r = list("PF8");
        assertThat(r.has("navigate")).isFalse();
        assertThat(r.at("/fields/pageNum").asText()).isEqualTo("2");
        assertThat(r.at("/fields/rows/0/accountId").asText()).isEqualTo("00000000035");
        assertThat(r.at("/fields/rows/0/cardNum").asText()).isEqualTo("************1600");
        assertThat(r.at("/fields/rows/6/accountId").asText()).isEqualTo("00000000010");
        assertThat(r.at("/fields/rows/6/cardNum").asText()).isEqualTo("************7560");
        assertThat(r.at("/fields/info").asText()).isEqualTo(INFO);
    }

    @Test
    @Covers({"TS-COCRDLIC-03", "BR-COCRDLIC-14", "BR-COCRDLIC-15", "Q-COCRDLIC-17", "BR-COCRDLIC-12"})
    void the_end_of_the_list_gives_the_last_page_messages() {
        JsonNode p7 = toPage(7);
        assertThat(p7.at("/fields/pageNum").asText()).isEqualTo("7");
        assertThat(p7.has("message")).isFalse();
        JsonNode p8 = list("PF8");
        assertThat(p8.at("/fields/pageNum").asText()).isEqualTo("8");
        assertThat(cards(p8)).containsExactly("************6588", "", "", "", "", "", "");
        assertThat(p8.at("/fields/rows/0/accountId").asText()).isEqualTo("00000000040");
        assertThat(p8.at("/message").asText()).isEqualTo(NO_MORE_RECORDS);
        assertThat(p8.at("/messageColor").asText()).isEqualTo("RED");
        assertThat(p8.at("/fields/info").asText()).isEqualTo(INFO);
        assertThat(p8.at("/fields/rows/0/selectable").asBoolean()).isTrue();
        assertThat(p8.at("/fields/rows/1/selectable").asBoolean()).isFalse();
        JsonNode again = list("PF8");
        assertThat(cards(again)).containsExactly("************6588", "", "", "", "", "", "");
        assertThat(again.at("/message").asText()).isEqualTo("NO MORE PAGES TO DISPLAY");
        assertThat(again.at("/fields/info").asText()).isEmpty();
        JsonNode enter = list("ENTER");
        assertThat(enter.at("/fields/pageNum").asText()).isEqualTo("8");
        JsonNode reset = list("PF8");
        assertThat(reset.at("/message").asText()).isEqualTo(NO_MORE_RECORDS);
        assertThat(reset.at("/fields/info").asText()).isEqualTo(INFO);
    }

    @Test
    @Covers({"TS-COCRDLIC-04", "BR-COCRDLIC-06", "NAV-COCRDLIC-05"})
    void pf7_shows_the_previous_page_in_ascending_order() {
        toPage(2);
        JsonNode r = list("PF7");
        assertThat(r.at("/fields/pageNum").asText()).isEqualTo("1");
        assertThat(cards(r)).containsExactlyElementsOf(page(1));
        assertThat(r.has("message")).isFalse();
        assertThat(list("PF8").at("/fields/pageNum").asText()).isEqualTo("2");
    }

    @Test
    @Covers({"TS-COCRDLIC-05", "NAV-COCRDLIC-05", "BR-COCRDLIC-14"})
    void pf7_on_page_one_reports_no_previous_pages() {
        admin.show("COCRDLIC");
        JsonNode r = list("PF7");
        assertThat(r.at("/fields/pageNum").asText()).isEqualTo("1");
        assertThat(cards(r)).containsExactlyElementsOf(page(1));
        assertThat(r.at("/message").asText()).isEqualTo("NO PREVIOUS PAGES TO DISPLAY");
        assertThat(r.at("/fields/info").asText()).isEmpty();
    }

    @Test
    @Covers({"TS-COCRDLIC-06", "BR-COCRDLIC-03", "VR-COCRDLIC-01", "BR-COCRDLIC-13"})
    void an_account_filter_shows_only_that_accounts_cards() {
        admin.show("COCRDLIC");
        JsonNode r = list("ENTER", ACCT1, "", sel());
        assertThat(cards(r)).containsExactly("************5740", "", "", "", "", "", "");
        assertThat(r.at("/message").asText()).isEqualTo(NO_MORE_RECORDS);
        assertThat(r.at("/fields/accountFilter").asText()).isEqualTo(ACCT1);
        assertThat(r.at("/fields/info").asText()).isEqualTo(INFO);
        JsonNode pf8 = list("PF8", ACCT1, "", sel());
        assertThat(cards(pf8)).containsExactly("************5740", "", "", "", "", "", "");
        assertThat(pf8.at("/fields/pageNum").asText()).isEqualTo("1");
        assertThat(pf8.at("/message").asText()).isEqualTo(NO_MORE_RECORDS);
    }

    @Test
    @Covers({"TS-COCRDLIC-07", "VR-COCRDLIC-01", "BR-COCRDLIC-12", "BR-COCRDLIC-13"})
    void a_short_account_filter_is_rejected_and_the_rows_stay() {
        admin.show("COCRDLIC");
        JsonNode r = list("ENTER", "50", "", sel());
        assertThat(r.at("/message").asText()).isEqualTo("ACCOUNT FILTER,IF SUPPLIED MUST BE A 11 DIGIT NUMBER");
        assertThat(r.at("/fields/errors").toString()).isEqualTo("[\"accountFilter\"]");
        assertThat(r.at("/focus").asText()).isEqualTo("accountFilter");
        assertThat(r.at("/fields/accountFilter").asText()).isEqualTo("50");
        assertThat(cards(r)).containsExactlyElementsOf(page(1));
        r.at("/fields/rows").forEach(row -> assertThat(row.get("selectable").asBoolean()).isFalse());
        assertThat(r.at("/fields/info").asText()).isEmpty();
    }

    @Test
    @Covers({"TS-COCRDLIC-08", "VR-COCRDLIC-02", "BR-COCRDLIC-03"})
    void a_card_filter_shows_that_card_masked_and_the_mask_keeps_the_filter() {
        admin.show("COCRDLIC");
        JsonNode r = list("ENTER", "", "4011500891777367", sel());
        assertThat(cards(r)).containsExactly("************7367", "", "", "", "", "", "");
        assertThat(r.at("/fields/rows/0/accountId").asText()).isEqualTo("00000000013");
        assertThat(r.at("/fields/rows/0/status").asText()).isEqualTo("Y");
        assertThat(r.at("/fields/cardFilter").asText()).isEqualTo("************7367");
        JsonNode kept = list("ENTER", "", "************7367", sel());
        assertThat(cards(kept)).containsExactly("************7367", "", "", "", "", "", "");
        assertThat(kept.toString()).doesNotContain("4011500891777367");
        JsonNode bad = list("ENTER", "", "40115008ABCD", sel());
        assertThat(bad.at("/message").asText()).isEqualTo("CARD ID FILTER,IF SUPPLIED MUST BE A 16 DIGIT NUMBER");
        assertThat(bad.at("/fields/errors").toString()).isEqualTo("[\"cardFilter\"]");
        assertThat(bad.at("/focus").asText()).isEqualTo("cardFilter");
        assertThat(cards(bad)).containsExactly("************7367", "", "", "", "", "", "");
    }

    @Test
    @Covers({"TS-COCRDLIC-09", "VR-COCRDLIC-01", "VR-COCRDLIC-02"})
    void both_filters_invalid_give_the_account_message_and_both_in_error() {
        admin.show("COCRDLIC");
        JsonNode r = list("ENTER", "ABC", "XYZ", sel());
        assertThat(r.at("/message").asText()).isEqualTo("ACCOUNT FILTER,IF SUPPLIED MUST BE A 11 DIGIT NUMBER");
        assertThat(r.at("/fields/errors").toString()).isEqualTo("[\"accountFilter\",\"cardFilter\"]");
        assertThat(r.at("/focus").asText()).isEqualTo("accountFilter");
        assertThat(r.at("/fields/cardFilter").asText()).isEqualTo("XYZ");
    }

    @Test
    @Covers({"TS-COCRDLIC-10", "BR-COCRDLIC-14"})
    void an_account_without_cards_gives_no_records_found_without_info() {
        admin.show("COCRDLIC");
        JsonNode r = list("ENTER", "00000000099", "", sel());
        assertThat(cards(r)).containsOnly("");
        assertThat(r.at("/message").asText()).isEqualTo(NO_RECORDS_FOUND);
        assertThat(r.at("/fields/info").asText()).isEmpty();
    }

    @Test
    @Covers({"TS-COCRDLIC-11", "BR-COCRDLIC-03"})
    void both_filters_must_match() {
        admin.show("COCRDLIC");
        JsonNode r = list("ENTER", ACCT1, CARD2, sel());
        assertThat(cards(r)).containsOnly("");
        assertThat(r.at("/message").asText()).isEqualTo(NO_RECORDS_FOUND);
    }

    @Test
    @Covers({"TS-COCRDLIC-12", "NAV-COCRDLIC-06", "BR-COCRDLIC-10", "BR-COCRDLIC-09", "Q-COCRDLIC-16"})
    void s_on_a_row_opens_the_view_for_that_card() {
        admin.show("COCRDLIC");
        JsonNode r = list("ENTER", "", "", sel(1, "S"));
        assertThat(r.at("/navigate").asText()).isEqualTo("COCRDSLC");
        JsonNode view = admin.show("COCRDSLC");
        assertThat(view.at("/fields/accountId").asText()).isEqualTo(ACCT1);
        assertThat(view.at("/fields/cardNum").asText()).isEqualTo("************5740");
        assertThat(view.at("/fields/keysProtected").asBoolean()).isTrue();
    }

    @Test
    @Covers({"TS-COCRDLIC-13", "NAV-COCRDLIC-07", "BR-COCRDLIC-10"})
    void u_on_a_row_opens_the_update_for_that_card() {
        admin.show("COCRDLIC");
        JsonNode r = list("ENTER", "", "", sel(2, "U"));
        assertThat(r.at("/navigate").asText()).isEqualTo("COCRDUPC");
        JsonNode upd = admin.show("COCRDUPC");
        assertThat(upd.at("/fields/accountId").asText()).isEqualTo(ACCT2);
        assertThat(upd.at("/fields/cardNum").asText()).isEqualTo("************1516");
        assertThat(upd.at("/fields/state").asText()).isEqualTo("S");
    }

    @Test
    @Covers({"BR-COCRDLIC-09"})
    void paging_ignores_a_single_selection() {
        admin.show("COCRDLIC");
        JsonNode r = list("PF8", "", "", sel(1, "S"));
        assertThat(r.has("navigate")).isFalse();
        assertThat(r.at("/fields/pageNum").asText()).isEqualTo("2");
        assertThat(r.at("/fields/rows/0/sel").asText()).isEmpty();
    }

    @Test
    @Covers({"TS-COCRDLIC-14", "VR-COCRDLIC-03", "Q-COCRDLIC-02", "Q-COCRDLIC-10", "BR-COCRDLIC-11"})
    void two_selections_keep_the_same_page_with_both_rows_in_error() {
        toPage(2);
        JsonNode r = list("ENTER", "", "", sel(1, "S", 3, "U"));
        assertThat(r.at("/message").asText()).isEqualTo("PLEASE SELECT ONLY ONE RECORD TO VIEW OR UPDATE");
        assertThat(r.at("/fields/pageNum").asText()).isEqualTo("2");
        assertThat(cards(r)).containsExactlyElementsOf(page(2));
        assertThat(r.at("/fields/rows/0/sel").asText()).isEqualTo("S");
        assertThat(r.at("/fields/rows/2/sel").asText()).isEqualTo("U");
        assertThat(r.at("/fields/rows/0/error").asBoolean()).isTrue();
        assertThat(r.at("/fields/rows/1/error").asBoolean()).isFalse();
        assertThat(r.at("/fields/rows/2/error").asBoolean()).isTrue();
        assertThat(r.at("/focus").asText()).isEqualTo("sel1");
        assertThat(r.at("/fields/info").asText()).isEqualTo(INFO);
        JsonNode next = list("PF8");
        assertThat(next.at("/fields/pageNum").asText()).isEqualTo("3");
        assertThat(cards(next)).containsExactlyElementsOf(page(3));
    }

    @Test
    @Covers({"TS-COCRDLIC-15", "VR-COCRDLIC-04", "Q-COCRDLIC-12"})
    void other_codes_including_lowercase_are_invalid() {
        admin.show("COCRDLIC");
        JsonNode r = list("ENTER", "", "", sel(2, "X"));
        assertThat(r.at("/message").asText()).isEqualTo("INVALID ACTION CODE");
        assertThat(r.at("/fields/rows/1/error").asBoolean()).isTrue();
        assertThat(r.at("/fields/rows/1/sel").asText()).isEqualTo("X");
        assertThat(r.at("/focus").asText()).isEqualTo("sel2");
        assertThat(cards(r)).containsExactlyElementsOf(page(1));
        JsonNode lower = list("ENTER", "", "", sel(1, "s"));
        assertThat(lower.has("navigate")).isFalse();
        assertThat(lower.at("/message").asText()).isEqualTo("INVALID ACTION CODE");
        assertThat(list("ENTER", "", "", sel(1, "u")).at("/message").asText()).isEqualTo("INVALID ACTION CODE");
    }

    @Test
    @Covers({"TS-COCRDLIC-16", "NAV-COCRDLIC-02", "Q-COCRDLIC-11"})
    void pf3_returns_to_the_main_menu_even_with_an_invalid_filter() {
        toPage(2);
        assertThat(list("PF3", "ABC", "", sel()).at("/navigate").asText()).isEqualTo("COMEN01C");
        assertThat(list("PF15", "", "", sel()).at("/navigate").asText()).isEqualTo("COMEN01C");
        assertThat(admin.show("COADM01C").has("fields")).isTrue();
    }

    @Test
    @Covers({"TS-COCRDLIC-17", "NAV-COCRDLIC-01", "ERR-COCRDLIC-04"})
    void other_keys_act_as_enter() {
        toPage(2);
        for (String key : List.of("PF5", "PF10", "CLEAR", "PA1", "PF1")) {
            JsonNode r = list(key);
            assertThat(r.at("/fields/pageNum").asText()).isEqualTo("2");
            assertThat(cards(r)).containsExactlyElementsOf(page(2));
            assertThat(r.has("message")).isFalse();
        }
        JsonNode mapfail = admin.post("COCRDLIC", "CLEAR", Map.of());
        assertThat(cards(mapfail)).containsExactlyElementsOf(page(2));
        assertThat(list("PF20").at("/fields/pageNum").asText()).isEqualTo("3");
        assertThat(list("PF19").at("/fields/pageNum").asText()).isEqualTo("2");
    }

    @Test
    @Covers({"TS-COCRDLIC-18", "Q-COCRDLIC-03"})
    void a_new_filter_starts_at_page_one() {
        toPage(2);
        JsonNode r = list("ENTER", ACCT1, "", sel());
        assertThat(r.at("/fields/pageNum").asText()).isEqualTo("1");
        assertThat(cards(r)).containsExactly("************5740", "", "", "", "", "", "");
        assertThat(r.at("/message").asText()).isEqualTo(NO_MORE_RECORDS);
        JsonNode cleared = list("ENTER", "", "", sel());
        assertThat(cleared.at("/fields/pageNum").asText()).isEqualTo("1");
        assertThat(cards(cleared)).containsExactlyElementsOf(page(1));
    }

    @Test
    @Covers({"TS-COCRDLIC-19", "NAV-COCRDLIC-03", "Q-COCRDLIC-11", "Q-COCRDLIC-13"})
    void returning_from_the_view_shows_page_one_unfiltered_and_keeps_the_user() {
        toPage(3);
        assertThat(list("ENTER", "", "", sel(1, "S")).at("/navigate").asText()).isEqualTo("COCRDSLC");
        admin.show("COCRDSLC");
        assertThat(admin.post("COCRDSLC", "PF3", Map.of()).at("/navigate").asText()).isEqualTo("COCRDLIC");
        JsonNode back = admin.show("COCRDLIC");
        assertThat(back.at("/fields/pageNum").asText()).isEqualTo("1");
        assertThat(cards(back)).containsExactlyElementsOf(page(1));
        assertThat(back.at("/fields/accountFilter").asText()).isEmpty();
        assertThat(admin.show("COADM01C").has("fields")).isTrue();
    }

    @Test
    @Covers({"TS-COCRDLIC-20", "Q-COCRDLIC-04"})
    void a_filter_matching_exactly_seven_cards_never_pages_to_an_empty_page() {
        for (int i = 1; i <= 6; i++) {
            insertCard(String.format("05000000000000%02d", i), 50);
        }
        admin.show("COCRDLIC");
        JsonNode r = list("ENTER", ACCT1, "", sel());
        assertThat(cards(r)).doesNotContain("").hasSize(7);
        assertThat(r.at("/message").asText()).isEqualTo(NO_MORE_RECORDS);
        JsonNode pf8 = list("PF8", ACCT1, "", sel());
        assertThat(pf8.at("/fields/pageNum").asText()).isEqualTo("1");
        assertThat(cards(pf8)).doesNotContain("");
        assertThat(pf8.at("/message").asText()).isEqualTo(NO_MORE_RECORDS);
        assertThat(pf8.at("/fields/info").asText()).isEqualTo(INFO);
        JsonNode again = list("PF8", ACCT1, "", sel());
        assertThat(again.at("/message").asText()).isEqualTo("NO MORE PAGES TO DISPLAY");
        assertThat(cards(again)).doesNotContain("");
    }

    @Test
    @Covers({"TS-COCRDLIC-21", "BR-COCRDLIC-01", "Q-COCRDLIC-01"})
    void a_regular_user_lists_only_cards_of_linked_accounts() {
        String user = ownerOf(50);
        ScreenClient c = signedOn(user);
        List<String> mine = allCards(user);
        assertThat(mine).isNotEmpty().hasSizeLessThan(50);
        JsonNode r = c.show("COCRDLIC");
        List<String> seen = new ArrayList<>();
        for (int guard = 0; guard < 20; guard++) {
            r.at("/fields/rows").forEach(row -> {
                if (!row.get("cardNum").asText().isEmpty()) {
                    seen.add(row.get("cardNum").asText());
                }
            });
            if (!r.at("/fields/pageNum").asText().isEmpty() && r.has("message")) {
                break;
            }
            r = list(c, "PF8", "", "", sel());
        }
        assertThat(seen).containsExactlyElementsOf(maskedAll(mine).subList(0, mine.size()));
        long other = jdbc.queryForObject("SELECT MIN(account_id) FROM user_account WHERE user_id <> ?", Long.class, user);
        JsonNode outside = list(c, "ENTER", String.format("%011d", other), "", sel());
        assertThat(outside.at("/message").asText()).isEqualTo(NO_RECORDS_FOUND);
        assertThat(cards(outside)).containsOnly("");
        JsonNode own = list(c, "ENTER", ACCT1, "", sel());
        assertThat(cards(own)).containsExactly("************5740", "", "", "", "", "", "");
    }

    @Test
    @Covers({"Q-COCRDLIC-06", "BR-COCRDLIC-08"})
    void page_numbers_above_nine_are_shown_in_full() {
        for (int i = 1; i <= 30; i++) {
            insertCard(String.format("00000000000000%02d", i), 1);
        }
        JsonNode r = toPage(10);
        assertThat(r.at("/fields/pageNum").asText()).isEqualTo("10");
        assertThat(list("PF8").at("/fields/pageNum").asText()).isEqualTo("11");
        assertThat(list("PF7").at("/fields/pageNum").asText()).isEqualTo("10");
        JsonNode p9 = list("PF7");
        assertThat(p9.at("/fields/pageNum").asText()).isEqualTo("9");
        List<String> all = allCards(null);
        assertThat(cards(p9)).containsExactlyElementsOf(maskedAll(all.subList(56, 63)));
    }

    @Test
    @Covers({"Q-COCRDLIC-07"})
    void pf7_reaching_the_first_card_early_shows_page_one_without_a_file_error() {
        toPage(2);
        jdbc.update("DELETE FROM card WHERE card_num = ?", CARD2);
        JsonNode r = list("PF7");
        assertThat(r.at("/fields/pageNum").asText()).isEqualTo("1");
        List<String> all = allCards(null);
        assertThat(cards(r)).containsExactlyElementsOf(maskedAll(all.subList(0, 7)));
        assertThat(r.has("message")).isFalse();
    }

    @Test
    @Covers({"Q-COCRDLIC-19", "ERR-COCRDLIC-02"})
    void an_empty_card_file_gives_no_records_found_instead_of_ending_the_task() {
        jdbc.update("DELETE FROM card");
        JsonNode r = admin.show("COCRDLIC");
        assertThat(r.at("/fields/pageNum").asText()).isEqualTo("1");
        assertThat(cards(r)).containsOnly("");
        assertThat(r.at("/message").asText()).isEqualTo(NO_RECORDS_FOUND);
        assertThat(r.at("/fields/info").asText()).isEmpty();
        JsonNode enter = list("ENTER");
        assertThat(enter.at("/message").asText()).isEqualTo(NO_RECORDS_FOUND);
        assertThat(cards(enter)).containsOnly("");
        JsonNode pf8 = list("PF8");
        assertThat(pf8.at("/message").asText()).isEqualTo(NO_RECORDS_FOUND);
        assertThat(pf8.at("/fields/pageNum").asText()).isEqualTo("1");
    }

    @Test
    @Covers({"Q-COCRDLIC-19", "ERR-COCRDLIC-02"})
    void a_browse_start_above_the_last_card_gives_the_end_of_list_messages() {
        List<String> all = allCards(null);
        toPage(7);
        jdbc.update("DELETE FROM card WHERE card_num = ?", all.get(49));
        JsonNode pf8 = list("PF8");
        assertThat(pf8.at("/fields/pageNum").asText()).isEqualTo("7");
        assertThat(cards(pf8)).containsExactlyElementsOf(maskedAll(all.subList(42, 49)));
        assertThat(pf8.at("/message").asText()).isEqualTo(NO_MORE_RECORDS);
        assertThat(pf8.at("/fields/info").asText()).isEqualTo(INFO);
        assertThat(list("PF8").at("/message").asText()).isEqualTo("NO MORE PAGES TO DISPLAY");
        jdbc.update("DELETE FROM card WHERE card_num >= ?", all.get(42));
        JsonNode enter = list("ENTER");
        assertThat(enter.at("/fields/pageNum").asText()).isEqualTo("7");
        assertThat(cards(enter)).containsOnly("");
        assertThat(enter.at("/message").asText()).isEqualTo(NO_MORE_RECORDS);
    }
}
