package com.carddemo.transaction;

import static org.assertj.core.api.Assertions.assertThat;

import com.carddemo.common.msg.CommonMessages;
import com.carddemo.test.Covers;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** COTRN00C screen turns. ADMIN sees the whole table, so the spec's record positions apply as written. */
class TransactionListScreenTest extends TransactionScreenTestBase {

    static final String TOP_REACHED = "You have reached the top of the page...";
    static final String BOTTOM_REACHED = "You have reached the bottom of the page...";
    static final String ALREADY_TOP = "You are already at the top of the page...";
    static final String ALREADY_BOTTOM = "You are already at the bottom of the page...";

    private JsonNode key(String key) {
        return client.post("COTRN00C", key, Map.of("searchTranId", ""));
    }

    private JsonNode search(String id) {
        return client.post("COTRN00C", "ENTER", Map.of("searchTranId", id));
    }

    private JsonNode select(int row, String value) {
        List<String> sel = new ArrayList<>(List.of("", "", "", "", "", "", "", "", "", ""));
        sel.set(row, value);
        return client.post("COTRN00C", "ENTER", Map.of("searchTranId", "", "selections", sel));
    }

    private static List<String> ids(JsonNode r) {
        List<String> out = new ArrayList<>();
        r.at("/fields/rows").forEach(n -> out.add(n.get("tranId").asText()));
        return out;
    }

    @Test
    @Covers({"TS-COTRN00C-01", "BR-COTRN00C-01", "BR-COTRN00C-02", "BR-COTRN00C-03", "BR-COTRN00C-04", "BR-COTRN00C-05",
        "NAV-COTRN00C-02", "Q-COTRN00C-12", "D-HISTORY"})
    void first_entry_shows_page_one_in_key_order() {
        client.signOn(ADMIN);
        JsonNode r = client.show("COTRN00C");
        assertThat(r.at("/fields/page").asText()).isEqualTo("00000001");
        assertThat(ids(r)).isEqualTo(allIds().subList(0, 10));
        JsonNode row1 = r.at("/fields/rows/0");
        assertThat(row1.get("tranId").asText()).isEqualTo("0000000000683580");
        assertThat(row1.get("date").asText()).isEqualTo("06/10/22");
        assertThat(row1.get("description").asText()).isEqualTo("Purchase at Abshire-Lowe");
        assertThat(row1.get("amount").asText()).isEqualTo("+00000504.77");
        JsonNode row2 = r.at("/fields/rows/1");
        assertThat(row2.get("tranId").asText()).isEqualTo("0000000001774260");
        assertThat(row2.get("description").asText()).isEqualTo("Return item at Nitzsche, N");
        assertThat(row2.get("amount").asText()).isEqualTo("-00000919.00");
        assertThat(r.has("message")).isFalse();
        assertThat(r.at("/focus").asText()).isEqualTo("searchTranId");
        assertThat(key("PF8").at("/fields/page").asText()).as("next-page flag is Y").isEqualTo("00000002");
    }

    @Test
    @Covers({"TS-COTRN00C-02", "TS-COTRN00C-03", "BR-COTRN00C-06", "BR-COTRN00C-07", "BR-COTRN00C-08", "ERR-COTRN00C-05",
        "Q-COTRN00C-05"})
    void forward_then_backward() {
        client.signOn(ADMIN);
        JsonNode first = client.show("COTRN00C");
        JsonNode p2 = key("PF8");
        assertThat(p2.at("/fields/page").asText()).isEqualTo("00000002");
        assertThat(p2.at("/fields/rows/0/tranId").asText()).isEqualTo("0000000025430891");
        assertThat(p2.at("/fields/rows/0/description").asText()).isEqualTo("Purchase at Beatty-Hessel");
        assertThat(p2.at("/fields/rows/0/amount").asText()).isEqualTo("+00000094.33");
        assertThat(p2.has("message")).isFalse();
        JsonNode back = key("PF7");
        assertThat(back.at("/fields/page").asText()).isEqualTo("00000001");
        assertThat(back.at("/fields/rows")).isEqualTo(first.at("/fields/rows"));
        assertThat(back.at("/message").asText()).isEqualTo(TOP_REACHED);
    }

    @Test
    @Covers({"TS-COTRN00C-04", "NAV-COTRN00C-05"})
    void backward_on_page_one_keeps_the_rows() {
        client.signOn(ADMIN);
        JsonNode first = client.show("COTRN00C");
        JsonNode r = key("PF7");
        assertThat(r.at("/message").asText()).isEqualTo(ALREADY_TOP);
        assertThat(r.at("/fields/rows")).isEqualTo(first.at("/fields/rows"));
        assertThat(r.at("/fields/page").asText()).isEqualTo("00000001");
    }

    @Test
    @Covers({"TS-COTRN00C-05", "BR-COTRN00C-05", "ERR-COTRN00C-03", "NAV-COTRN00C-06", "Q-COTRN00C-05"})
    void forward_to_the_last_page() {
        client.signOn(ADMIN);
        client.show("COTRN00C");
        JsonNode r = null;
        for (int i = 0; i < 29; i++) {
            r = key("PF8");
        }
        assertThat(r.at("/fields/page").asText()).isEqualTo("00000030");
        assertThat(ids(r)).isEqualTo(allIds().subList(290, 300));
        assertThat(r.at("/fields/rows/9/tranId").asText()).isEqualTo("0000000996722787");
        assertThat(r.at("/message").asText()).isEqualTo(BOTTOM_REACHED);
        JsonNode again = key("PF8");
        assertThat(again.at("/message").asText()).isEqualTo(ALREADY_BOTTOM);
        assertThat(again.at("/fields/rows")).isEqualTo(r.at("/fields/rows"));
        assertThat(again.at("/fields/page").asText()).isEqualTo("00000030");
    }

    @Test
    @Covers({"TS-COTRN00C-06", "BR-COTRN00C-03", "BR-COTRN00C-06", "BR-COTRN00C-10", "Q-COTRN00C-03"})
    void search_starts_page_one_at_the_id() {
        client.signOn(ADMIN);
        client.show("COTRN00C");
        JsonNode r = search("0000000957695517");
        assertThat(r.at("/fields/page").asText()).isEqualTo("00000001");
        assertThat(ids(r)).isEqualTo(allIds().subList(289, 299));
        assertThat(r.at("/fields/searchTranId").asText()).isEmpty();
        JsonNode p2 = key("PF8");
        assertThat(p2.at("/fields/page").asText()).isEqualTo("00000002");
        assertThat(ids(p2)).containsExactly("0000000996722787");
        assertThat(p2.at("/message").asText()).isEqualTo(BOTTOM_REACHED);
        assertThat(key("PF8").at("/message").asText()).as("next-page flag N").isEqualTo(ALREADY_BOTTOM);
        JsonNode back = key("PF7");
        assertThat(back.at("/fields/page").asText()).isEqualTo("00000001");
        assertThat(back.has("message")).isFalse();
        assertThat(key("PF7").at("/message").asText()).isEqualTo(ALREADY_TOP);
    }

    @Test
    @Covers({"TS-COTRN00C-13", "Q-COTRN00C-13", "BR-COTRN00C-08", "NAV-COTRN00C-05", "NAV-COTRN00C-06"})
    void backward_on_a_last_page_one_does_not_open_a_next_page() {
        client.signOn(ADMIN);
        client.show("COTRN00C");
        JsonNode first = search("0000000957864065");
        assertThat(first.at("/fields/page").asText()).isEqualTo("00000001");
        assertThat(ids(first)).isEqualTo(allIds().subList(290, 300));
        assertThat(first.at("/message").asText()).isEqualTo(BOTTOM_REACHED);
        assertThat(key("PF7").at("/message").asText()).isEqualTo(ALREADY_TOP);
        JsonNode r = key("PF8");
        assertThat(r.at("/message").asText()).isEqualTo(ALREADY_BOTTOM);
        assertThat(r.at("/fields/rows")).isEqualTo(first.at("/fields/rows"));
        assertThat(r.at("/fields/page").asText()).isEqualTo("00000001");

        JsonNode partial = search("0000000996722787");
        assertThat(ids(partial)).containsExactly("0000000996722787");
        key("PF7");
        JsonNode again = key("PF8");
        assertThat(again.at("/message").asText()).as("partial last page 1").isEqualTo(ALREADY_BOTTOM);
        assertThat(again.at("/fields/rows")).isEqualTo(partial.at("/fields/rows"));
        assertThat(again.at("/fields/page").asText()).isEqualTo("00000001");
    }

    @Test
    @Covers({"TS-COTRN00C-07", "VR-COTRN00C-02", "Q-COTRN00C-04", "Q-COTRN00C-07", "C-KEYLEN"})
    void search_id_must_be_16_digits_and_keeps_the_current_page() {
        client.signOn(ADMIN);
        client.show("COTRN00C");
        JsonNode p2 = key("PF8");
        for (String bad : new String[] {"683580", "ABC", "00000000006835 0"}) {
            JsonNode r = search(bad);
            assertThat(r.at("/message").asText()).as(bad).isEqualTo("Tran ID must be Numeric ...");
            assertThat(r.at("/focus").asText()).isEqualTo("searchTranId");
            assertThat(r.at("/fields/searchTranId").asText()).isEqualTo(bad);
            assertThat(r.at("/fields/page").asText()).isEqualTo("00000002");
            assertThat(r.at("/fields/rows")).isEqualTo(p2.at("/fields/rows"));
        }
        assertThat(key("PF8").at("/fields/page").asText()).as("paging position kept").isEqualTo("00000003");
    }

    @Test
    @Covers({"TS-COTRN00C-08", "BR-COTRN00C-09", "NAV-COTRN00C-03", "NAV-COTRN00C-08", "TS-COTRN01C-01", "BR-COTRN01C-03"})
    void s_on_a_row_opens_the_view_with_that_transaction() {
        client.signOn(ADMIN);
        client.show("COTRN00C");
        List<String> sel = new ArrayList<>(List.of("", "s", "S", "", "", "", "", "", "", ""));
        JsonNode r = client.post("COTRN00C", "ENTER", Map.of("searchTranId", "ABC", "selections", sel));
        assertThat(r.at("/navigate").asText()).as("selection is checked before the search ID").isEqualTo("COTRN01C");
        JsonNode view = client.show("COTRN01C");
        assertThat(view.at("/fields/tranIdIn").asText()).isEqualTo("0000000001774260");
        assertThat(view.at("/fields/tranId").asText()).isEqualTo("0000000001774260");
        assertThat(view.at("/fields/cardNum").asText()).isEqualTo("************6232");
        assertThat(view.at("/fields/typeCd").asText()).isEqualTo("03");
        assertThat(view.at("/fields/catCd").asText()).isEqualTo("0001");
        assertThat(view.at("/fields/source").asText()).isEqualTo("OPERATOR");
        assertThat(view.at("/fields/description").asText()).isEqualTo("Return item at Nitzsche, Nicolas and Lowe");
        assertThat(view.at("/fields/amount").asText()).isEqualTo("-00000919.00");
        assertThat(view.at("/fields/origDate").asText()).isEqualTo("2022-06-10");
        assertThat(view.at("/fields/procDate").asText()).isEmpty();
        assertThat(view.at("/fields/merchantId").asText()).isEqualTo("800000000");
        assertThat(view.at("/fields/merchantName").asText()).isEqualTo("Nitzsche, Nicolas and Lowe");
        assertThat(view.at("/fields/merchantCity").asText()).isEqualTo("Fidelshire");
        assertThat(view.at("/fields/merchantZip").asText()).isEqualTo("53378");
        assertThat(view.has("message")).isFalse();
    }

    @Test
    @Covers({"VR-COTRN00C-01", "Q-COTRN00C-06"})
    void a_mark_on_an_empty_row_is_ignored() {
        client.signOn(ADMIN);
        client.show("COTRN00C");
        search("0000000996722787");
        JsonNode r = select(5, "S");
        assertThat(r.has("navigate")).isFalse();
        assertThat(r.has("message")).as("no invalid-selection message either").isFalse();
        assertThat(r.at("/fields/rows/0/tranId").asText()).as("rebuilt from the start").isEqualTo("0000000000683580");
    }

    @Test
    @Covers({"TS-COTRN00C-09", "VR-COTRN00C-01", "Q-COTRN00C-06"})
    void invalid_selection_rebuilds_page_one_and_keeps_its_message() {
        client.signOn(ADMIN);
        JsonNode first = client.show("COTRN00C");
        key("PF8");
        JsonNode r = select(0, "X");
        assertThat(r.at("/message").asText()).isEqualTo("Invalid selection. Valid value is S");
        assertThat(r.at("/focus").asText()).isEqualTo("searchTranId");
        assertThat(r.at("/fields/page").asText()).isEqualTo("00000001");
        assertThat(r.at("/fields/rows")).isEqualTo(first.at("/fields/rows"));

        search("0000000996722787");
        JsonNode last = select(0, "X");
        assertThat(last.at("/message").asText()).as("not replaced by the bottom message").isEqualTo("Invalid selection. Valid value is S");
    }

    @Test
    @Covers({"TS-COTRN00C-10", "Q-COTRN00C-02", "ERR-COTRN00C-01", "ERR-COTRN00C-07"})
    void search_beyond_the_last_id_shows_an_empty_page_one() {
        client.signOn(ADMIN);
        client.show("COTRN00C");
        JsonNode r = search("9999999999999999");
        assertThat(r.at("/fields/rows").size()).isZero();
        assertThat(r.at("/fields/page").asText()).isEqualTo("00000001");
        assertThat(r.at("/message").asText()).isEqualTo("Transaction ID NOT found...");
    }

    @Test
    @Covers({"TS-COTRN00C-11", "Q-COTRN00C-01", "TS-COTRN01C-09", "NAV-COTRN01C-06", "Q-COTRN01C-03"})
    void returning_from_the_view_with_f5_skips_nothing() {
        client.signOn(ADMIN);
        client.show("COTRN00C");
        select(0, "S");
        client.show("COTRN01C");
        assertThat(client.post("COTRN01C", "PF5", Map.of()).at("/navigate").asText()).isEqualTo("COTRN00C");
        JsonNode r = client.show("COTRN00C");
        assertThat(r.at("/fields/rows/0/tranId").asText()).isEqualTo("0000000000683580");
        assertThat(r.at("/fields/page").asText()).isEqualTo("00000001");
    }

    @Test
    @Covers({"TS-COTRN00C-12", "NAV-COTRN00C-04", "NAV-COTRN00C-07"})
    void keys() {
        client.signOn(ADMIN);
        JsonNode first = client.show("COTRN00C");
        for (String k : new String[] {"PF10", "PF1", "PF5", "PA1", "CLEAR"}) {
            JsonNode r = client.post("COTRN00C", k, Map.of("searchTranId", "12"));
            assertThat(r.at("/message").asText()).as(k).isEqualTo(CommonMessages.INVALID_KEY);
            assertThat(r.at("/fields/rows")).isEqualTo(first.at("/fields/rows"));
            assertThat(r.at("/fields/searchTranId").asText()).isEqualTo("12");
        }
        assertThat(key("PF3").at("/navigate").asText()).isEqualTo("COMEN01C");
    }

    @Test
    @Covers({"NAV-COTRN00C-01", "C-ROLE"})
    void without_a_session_the_list_is_refused() {
        assertThat(client.showRaw("COTRN00C").getStatus()).isEqualTo(401);
        assertThat(client.turn("COTRN00C", "PF8", Map.of()).getStatus()).isEqualTo(401);
    }

    @Test
    @Covers({"Q-COTRN00C-08", "C-SCOPE", "D-USER-ACCT"})
    void a_user_lists_only_transactions_of_linked_accounts() {
        String user = ownerOf(1);
        List<String> mine = inScopeIds(user);
        assertThat(mine).isNotEmpty().hasSizeLessThan(allIds().size());
        client.signOn(user);
        JsonNode r = client.show("COTRN00C");
        assertThat(ids(r)).isEqualTo(mine.subList(0, Math.min(10, mine.size())));
        String outside = allIds().stream().filter(id -> !mine.contains(id)).findFirst().orElseThrow();
        JsonNode s = search(outside);
        assertThat(ids(s)).allMatch(mine::contains);
        assertThat(ids(s)).first().isEqualTo(mine.stream().filter(id -> id.compareTo(outside) >= 0).findFirst().orElseThrow());
    }

    @Test
    @Covers({"Q-COTRN00C-08", "C-SCOPE"})
    void a_user_with_no_transactions_gets_an_empty_page() {
        String user = ownerOf(1);
        jdbc.update("DELETE FROM card_transaction WHERE card_num IN (SELECT x.card_num FROM card_xref x "
            + "JOIN user_account ua ON ua.account_id = x.account_id WHERE ua.user_id = ?)", user);
        client.signOn(user);
        JsonNode r = client.show("COTRN00C");
        assertThat(r.at("/fields/rows").size()).isZero();
        assertThat(r.at("/message").asText()).isEqualTo("Transaction ID NOT found...");
        assertThat(key("PF8").at("/message").asText()).isEqualTo(ALREADY_BOTTOM);
        assertThat(key("PF7").at("/message").asText()).isEqualTo(ALREADY_TOP);
    }

    @Test
    @Covers({"Q-COTRN00C-10"})
    void a_deleted_boundary_record_skips_nothing() {
        client.signOn(ADMIN);
        client.show("COTRN00C");
        List<String> all = allIds();
        jdbc.update("DELETE FROM card_transaction WHERE tran_id = ?", all.get(9));
        JsonNode p2 = key("PF8");
        assertThat(p2.at("/fields/rows/0/tranId").asText()).isEqualTo(all.get(10));
        jdbc.update("DELETE FROM card_transaction WHERE tran_id = ?", all.get(10));
        JsonNode p1 = key("PF7");
        assertThat(p1.at("/fields/rows/0/tranId").asText()).isEqualTo(all.get(0));
    }

    @Test
    @Covers({"Q-COTRN00C-11"})
    void a_backward_page_reaching_the_start_early_shows_a_full_first_page() {
        client.signOn(ADMIN);
        client.show("COTRN00C");
        key("PF8");
        List<String> all = allIds();
        for (int i = 0; i < 5; i++) {
            jdbc.update("DELETE FROM card_transaction WHERE tran_id = ?", all.get(i));
        }
        JsonNode r = key("PF7");
        assertThat(r.at("/fields/page").asText()).isEqualTo("00000001");
        assertThat(ids(r)).isEqualTo(all.subList(5, 15));
        assertThat(r.at("/message").asText()).isEqualTo(TOP_REACHED);
    }

    @Test
    @Covers({"D-HISTORY"})
    void transactions_added_online_appear_in_key_order() {
        client.signOn(ADMIN);
        jdbc.update("""
            INSERT INTO card_transaction (tran_id, type_cd, cat_cd, amount, card_num, orig_ts_text, description)
            VALUES ('0000000000000002', '01', 1, 1.00, (SELECT MIN(card_num) FROM card_xref), '2021-01-02', 'Earlier cycle')
            """);
        JsonNode r = client.show("COTRN00C");
        assertThat(r.at("/fields/rows/0/tranId").asText()).isEqualTo("0000000000000002");
        assertThat(r.at("/fields/rows/0/date").asText()).isEqualTo("01/02/21");
    }
}
