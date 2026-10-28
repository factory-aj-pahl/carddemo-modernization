package com.carddemo.card;

import static org.assertj.core.api.Assertions.assertThat;

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

/** COCRDSLC credit card view from the list and from the main menu. */
@SpringBootTest(classes = TestApplication.class)
@AutoConfigureMockMvc
class CardViewTest extends CardScreenTestSupport {

    static final String FOUND = "   Displaying requested details";
    static final String PROMPT = "Please enter Account and Card Number";
    static final String NOT_FOUND = "Did not find cards for this search condition";

    JsonNode view(ScreenClient c, String key, String acct, String card) {
        Map<String, Object> f = new HashMap<>();
        f.put("accountId", acct);
        f.put("cardNum", card);
        return c.post("COCRDSLC", key, f);
    }

    JsonNode view(String acct, String card) {
        return view(admin, "ENTER", acct, card);
    }

    JsonNode fromList(ScreenClient c, int row) {
        c.show("COCRDLIC");
        List<String> sel = sel(row, "S");
        Map<String, Object> f = new HashMap<>();
        f.put("sel", sel);
        assertThat(c.post("COCRDLIC", "ENTER", f).at("/navigate").asText()).isEqualTo("COCRDSLC");
        return c.show("COCRDSLC");
    }

    static void assertNoDetails(JsonNode r) {
        assertThat(r.at("/fields/name").asText()).isEmpty();
        assertThat(r.at("/fields/status").asText()).isEmpty();
        assertThat(r.at("/fields/expMonth").asText()).isEmpty();
        assertThat(r.at("/fields/expYear").asText()).isEmpty();
    }

    @Test
    @Covers({"TS-COCRDSLC-01", "BR-COCRDSLC-02", "BR-COCRDSLC-03", "BR-COCRDSLC-05", "BR-COCRDSLC-04", "BR-COCRDSLC-07",
        "Q-COCRDSLC-10", "Q-COCRDSLC-11"})
    void from_the_list_the_selected_card_is_shown_with_protected_keys() {
        Map<String, Object> before = cardRow(CARD1);
        JsonNode r = fromList(admin, 1);
        assertThat(r.at("/fields/accountId").asText()).isEqualTo(ACCT1);
        assertThat(r.at("/fields/cardNum").asText()).isEqualTo("************5740");
        assertThat(r.at("/fields/keysProtected").asBoolean()).isTrue();
        assertThat(r.at("/fields/name").asText()).isEqualTo("Aniya Von");
        assertThat(r.at("/fields/status").asText()).isEqualTo("Y");
        assertThat(r.at("/fields/expMonth").asText()).isEqualTo("03");
        assertThat(r.at("/fields/expYear").asText()).isEqualTo("2023");
        assertThat(r.at("/fields/info").asText()).isEqualTo(FOUND);
        assertThat(r.has("message")).isFalse();
        assertThat(r.at("/focus").asText()).isEqualTo("accountId");
        assertThat(r.toString()).doesNotContain(CARD1).doesNotContain("747");
        assertThat(cardRow(CARD1)).isEqualTo(before);
    }

    @Test
    @Covers({"TS-COCRDSLC-02", "NAV-COCRDSLC-04"})
    void from_the_menu_the_search_screen_is_empty() {
        JsonNode r = admin.show("COCRDSLC");
        assertThat(r.at("/fields/accountId").asText()).isEmpty();
        assertThat(r.at("/fields/cardNum").asText()).isEmpty();
        assertThat(r.at("/fields/keysProtected").asBoolean()).isFalse();
        assertNoDetails(r);
        assertThat(r.at("/fields/info").asText()).isEqualTo(PROMPT);
        assertThat(r.has("message")).isFalse();
        assertThat(r.at("/focus").asText()).isEqualTo("accountId");
    }

    @Test
    @Covers({"TS-COCRDSLC-03", "BR-COCRDSLC-01", "NAV-COCRDSLC-03", "Q-COCRDSLC-11"})
    void typed_keys_show_the_card_and_the_masked_number_rereads_it() {
        admin.show("COCRDSLC");
        JsonNode r = view(ACCT2, CARD2);
        assertThat(r.at("/fields/name").asText()).isEqualTo("Ward Jones");
        assertThat(r.at("/fields/status").asText()).isEqualTo("Y");
        assertThat(r.at("/fields/expMonth").asText()).isEqualTo("07");
        assertThat(r.at("/fields/expYear").asText()).isEqualTo("2025");
        assertThat(r.at("/fields/cardNum").asText()).isEqualTo("************1516");
        assertThat(r.at("/fields/info").asText()).isEqualTo(FOUND);
        assertThat(r.toString()).doesNotContain(CARD2);
        JsonNode again = view(ACCT2, "************1516");
        assertThat(again.at("/fields/name").asText()).isEqualTo("Ward Jones");
        assertThat(again.has("message")).isFalse();
        JsonNode other = view(ACCT1, CARD1);
        assertThat(other.at("/fields/name").asText()).isEqualTo("Aniya Von");
    }

    @Test
    @Covers({"TS-COCRDSLC-04", "VR-COCRDSLC-05", "BR-COCRDSLC-06", "VR-COCRDSLC-07", "ERR-COCRDSLC-04"})
    void no_keys_give_no_input_received_with_both_stars() {
        admin.show("COCRDSLC");
        JsonNode r = view("", "");
        assertThat(r.at("/message").asText()).isEqualTo("No input received");
        assertThat(r.at("/fields/accountId").asText()).isEqualTo("*");
        assertThat(r.at("/fields/cardNum").asText()).isEqualTo("*");
        assertThat(r.at("/fields/errors").toString()).isEqualTo("[\"accountId\",\"cardNum\"]");
        assertThat(r.at("/focus").asText()).isEqualTo("accountId");
        assertThat(r.at("/fields/info").asText()).isEqualTo(PROMPT);
        JsonNode stars = view("*", "*");
        assertThat(stars.at("/message").asText()).isEqualTo("No input received");
        JsonNode mapfail = admin.post("COCRDSLC", "ENTER", Map.of());
        assertThat(mapfail.at("/message").asText()).isEqualTo("No input received");
    }

    @Test
    @Covers({"TS-COCRDSLC-05", "TS-COCRDSLC-06", "VR-COCRDSLC-01", "VR-COCRDSLC-07"})
    void a_missing_or_zero_account_is_not_provided() {
        admin.show("COCRDSLC");
        JsonNode r = view("", CARD2);
        assertThat(r.at("/message").asText()).isEqualTo("Account number not provided");
        assertThat(r.at("/fields/accountId").asText()).isEqualTo("*");
        assertThat(r.at("/fields/cardNum").asText()).isEqualTo("************1516");
        assertThat(r.at("/focus").asText()).isEqualTo("accountId");
        assertNoDetails(r);
        assertThat(view("00000000000", CARD2).at("/message").asText()).isEqualTo("Account number not provided");
    }

    @Test
    @Covers({"TS-COCRDSLC-07", "VR-COCRDSLC-03", "BR-COCRDSLC-06"})
    void a_missing_card_is_not_provided_with_focus_on_the_card() {
        admin.show("COCRDSLC");
        JsonNode r = view(ACCT2, "");
        assertThat(r.at("/message").asText()).isEqualTo("Card number not provided");
        assertThat(r.at("/fields/cardNum").asText()).isEqualTo("*");
        assertThat(r.at("/fields/accountId").asText()).isEqualTo(ACCT2);
        assertThat(r.at("/fields/errors").toString()).isEqualTo("[\"cardNum\"]");
        assertThat(r.at("/focus").asText()).isEqualTo("cardNum");
    }

    @Test
    @Covers({"TS-COCRDSLC-08", "VR-COCRDSLC-02", "Q-COCRDSLC-02", "Q-COCRDSLC-04"})
    void a_non_numeric_account_is_redisplayed_empty_in_error() {
        admin.show("COCRDSLC");
        JsonNode r = view("ABC", CARD2);
        assertThat(r.at("/message").asText()).isEqualTo("ACCOUNT FILTER,IF SUPPLIED MUST BE A 11 DIGIT NUMBER");
        assertThat(r.at("/fields/accountId").asText()).isEmpty();
        assertThat(r.at("/fields/errors").toString()).isEqualTo("[\"accountId\"]");
        assertThat(r.at("/focus").asText()).isEqualTo("accountId");
        assertThat(view("27", CARD2).at("/message").asText()).isEqualTo("ACCOUNT FILTER,IF SUPPLIED MUST BE A 11 DIGIT NUMBER");
    }

    @Test
    @Covers({"TS-COCRDSLC-09", "VR-COCRDSLC-04", "VR-COCRDSLC-07"})
    void a_non_numeric_card_is_rejected() {
        admin.show("COCRDSLC");
        JsonNode r = view(ACCT2, "0683ABC");
        assertThat(r.at("/message").asText()).isEqualTo("CARD ID FILTER,IF SUPPLIED MUST BE A 16 DIGIT NUMBER");
        assertThat(r.at("/fields/cardNum").asText()).isEmpty();
        assertThat(r.at("/focus").asText()).isEqualTo("cardNum");
        JsonNode both = view("ABC", "0683ABC");
        assertThat(both.at("/message").asText()).isEqualTo("ACCOUNT FILTER,IF SUPPLIED MUST BE A 11 DIGIT NUMBER");
        assertThat(both.at("/fields/errors").toString()).isEqualTo("[\"accountId\",\"cardNum\"]");
    }

    @Test
    @Covers({"TS-COCRDSLC-10", "VR-COCRDSLC-06"})
    void an_unknown_card_is_not_found_with_both_keys_in_error() {
        admin.show("COCRDSLC");
        JsonNode r = view(ACCT2, "0000000000000001");
        assertThat(r.at("/message").asText()).isEqualTo(NOT_FOUND);
        assertThat(r.at("/fields/errors").toString()).isEqualTo("[\"accountId\",\"cardNum\"]");
        assertThat(r.at("/fields/accountId").asText()).isEqualTo(ACCT2);
        assertThat(r.at("/fields/cardNum").asText()).isEqualTo("************0001");
        assertNoDetails(r);
        assertThat(r.at("/fields/info").asText()).isEqualTo(PROMPT);
        assertThat(r.at("/focus").asText()).isEqualTo("accountId");
    }

    @Test
    @Covers({"TS-COCRDSLC-11", "Q-COCRDSLC-01", "BR-COCRDSLC-01"})
    void a_card_of_another_account_is_not_found() {
        admin.show("COCRDSLC");
        JsonNode r = view("00000000001", CARD1);
        assertThat(r.at("/message").asText()).isEqualTo(NOT_FOUND);
        assertNoDetails(r);
        assertThat(r.at("/fields/errors").toString()).isEqualTo("[\"accountId\",\"cardNum\"]");
    }

    @Test
    @Covers({"Q-COCRDSLC-01"})
    void a_regular_user_sees_only_cards_of_linked_accounts() {
        ScreenClient owner = signedOn(ownerOf(27));
        owner.show("COCRDSLC");
        assertThat(view(owner, "ENTER", ACCT2, CARD2).at("/fields/name").asText()).isEqualTo("Ward Jones");
        ScreenClient other = signedOn(nonOwnerOf(27));
        other.show("COCRDSLC");
        JsonNode r = view(other, "ENTER", ACCT2, CARD2);
        assertThat(r.at("/message").asText()).isEqualTo(NOT_FOUND);
        assertNoDetails(r);
    }

    @Test
    @Covers({"TS-COCRDSLC-12", "NAV-COCRDSLC-02", "BR-COCRDSLC-08", "Q-COCRDSLC-03"})
    void pf3_returns_to_the_list_or_the_menu() {
        fromList(admin, 1);
        assertThat(view(admin, "PF3", ACCT1, "************5740").at("/navigate").asText()).isEqualTo("COCRDLIC");
        admin.show("COCRDSLC");
        assertThat(view(admin, "PF3", "", "").at("/navigate").asText()).isEqualTo("COMEN01C");
        admin.show("COCRDSLC");
        assertThat(view(admin, "PF15", "", "").at("/navigate").asText()).isEqualTo("COMEN01C");
        assertThat(admin.show("COADM01C").has("fields")).isTrue();
    }

    @Test
    @Covers({"TS-COCRDSLC-13", "NAV-COCRDSLC-01", "BR-COCRDSLC-05"})
    void other_keys_reread_the_same_card() {
        fromList(admin, 1);
        for (String key : List.of("PF7", "PF5", "CLEAR")) {
            JsonNode r = view(admin, key, "99999999999", "1111111111111111");
            assertThat(r.at("/fields/name").asText()).isEqualTo("Aniya Von");
            assertThat(r.at("/fields/accountId").asText()).isEqualTo(ACCT1);
            assertThat(r.at("/fields/keysProtected").asBoolean()).isTrue();
            assertThat(r.has("message")).isFalse();
        }
        admin.show("COCRDSLC");
        view(ACCT2, CARD2);
        assertThat(view(admin, "PF8", ACCT2, "************1516").at("/fields/name").asText()).isEqualTo("Ward Jones");
    }

    @Test
    @Covers({"ERR-COCRDSLC-03"})
    void a_turn_without_first_entry_is_a_search() {
        JsonNode r = view(ACCT2, CARD2);
        assertThat(r.at("/fields/name").asText()).isEqualTo("Ward Jones");
        assertThat(r.at("/fields/keysProtected").asBoolean()).isFalse();
    }
}
