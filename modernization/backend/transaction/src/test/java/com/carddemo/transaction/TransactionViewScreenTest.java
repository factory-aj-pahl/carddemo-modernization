package com.carddemo.transaction;

import static org.assertj.core.api.Assertions.assertThat;

import com.carddemo.common.msg.CommonMessages;
import com.carddemo.test.Covers;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** COTRN01C screen turns. */
class TransactionViewScreenTest extends TransactionScreenTestBase {

    static final List<String> DETAILS = List.of("tranId", "cardNum", "typeCd", "catCd", "source", "description", "amount",
        "origDate", "procDate", "merchantId", "merchantName", "merchantCity", "merchantZip");

    private JsonNode fetch(String id) {
        return client.post("COTRN01C", "ENTER", Map.of("tranIdIn", id));
    }

    private static void assertDetailsBlank(JsonNode r) {
        for (String f : DETAILS) {
            assertThat(r.at("/fields/" + f).asText()).as(f).isEmpty();
        }
    }

    @Test
    @Covers({"TS-COTRN01C-02", "NAV-COTRN01C-02"})
    void menu_entry_shows_an_empty_form() {
        client.signOn(ADMIN);
        JsonNode r = client.show("COTRN01C");
        assertThat(r.at("/fields/tranIdIn").asText()).isEmpty();
        assertDetailsBlank(r);
        assertThat(r.has("message")).isFalse();
        assertThat(r.at("/focus").asText()).isEqualTo("tranIdIn");
    }

    @Test
    @Covers({"TS-COTRN01C-03", "BR-COTRN01C-01", "BR-COTRN01C-02", "BR-COTRN01C-05", "NAV-COTRN01C-03", "Q-COTRN01C-05",
        "Q-COTRN01C-06", "Q-COTRN01C-08", "C-MASK"})
    void enter_fetches_and_masks_the_card() {
        client.signOn(ADMIN);
        client.show("COTRN01C");
        JsonNode r = fetch("0000000000683580");
        assertThat(r.at("/fields/tranIdIn").asText()).isEqualTo("0000000000683580");
        assertThat(r.at("/fields/tranId").asText()).isEqualTo("0000000000683580");
        assertThat(r.at("/fields/cardNum").asText()).isEqualTo("************7065");
        assertThat(r.at("/fields/typeCd").asText()).isEqualTo("01");
        assertThat(r.at("/fields/catCd").asText()).isEqualTo("0001");
        assertThat(r.at("/fields/source").asText()).isEqualTo("POS TERM");
        assertThat(r.at("/fields/description").asText()).isEqualTo("Purchase at Abshire-Lowe");
        assertThat(r.at("/fields/amount").asText()).isEqualTo("+00000504.77");
        assertThat(r.at("/fields/origDate").asText()).isEqualTo("2022-06-10");
        assertThat(r.at("/fields/procDate").asText()).isEmpty();
        assertThat(r.at("/fields/merchantId").asText()).isEqualTo("800000000");
        assertThat(r.at("/fields/merchantName").asText()).isEqualTo("Abshire-Lowe");
        assertThat(r.at("/fields/merchantCity").asText()).isEqualTo("North Enoshaven");
        assertThat(r.at("/fields/merchantZip").asText()).isEqualTo("72112");
        assertThat(r.has("message")).isFalse();
        String card = jdbc.queryForObject("SELECT card_num FROM card_transaction WHERE tran_id = '0000000000683580'", String.class);
        assertThat(r.toString()).doesNotContain(card);
    }

    @Test
    @Covers({"Q-COTRN01C-06", "BR-COTRN01C-02"})
    void long_values_are_cut_to_the_screen_lengths() {
        jdbc.update("""
            UPDATE card_transaction SET description = REPLICATE('D', 100), merchant_name = REPLICATE('N', 50),
              merchant_city = REPLICATE('C', 50), proc_ts_text = '2022-06-11 10:11:12.000000' WHERE tran_id = '0000000000683580'
            """);
        client.signOn(ADMIN);
        JsonNode r = fetch("0000000000683580");
        assertThat(r.at("/fields/description").asText()).isEqualTo("D".repeat(60));
        assertThat(r.at("/fields/merchantName").asText()).isEqualTo("N".repeat(30));
        assertThat(r.at("/fields/merchantCity").asText()).isEqualTo("C".repeat(25));
        assertThat(r.at("/fields/procDate").asText()).isEqualTo("2022-06-11");
    }

    @Test
    @Covers({"TS-COTRN01C-04", "VR-COTRN01C-01"})
    void blank_id() {
        client.signOn(ADMIN);
        for (String id : new String[] {"", "   "}) {
            JsonNode r = fetch(id);
            assertThat(r.at("/message").asText()).isEqualTo("Tran ID can NOT be empty...");
            assertThat(r.at("/focus").asText()).isEqualTo("tranIdIn");
            assertDetailsBlank(r);
        }
    }

    @Test
    @Covers({"TS-COTRN01C-05", "VR-COTRN01C-02", "BR-COTRN01C-04"})
    void unknown_id_blanks_the_details() {
        client.signOn(ADMIN);
        fetch("0000000000683580");
        JsonNode r = fetch("9999999999999999");
        assertThat(r.at("/message").asText()).isEqualTo("Transaction ID NOT found...");
        assertThat(r.at("/fields/tranIdIn").asText()).isEqualTo("9999999999999999");
        assertDetailsBlank(r);
    }

    @Test
    @Covers({"TS-COTRN01C-06", "Q-COTRN01C-04", "C-KEYLEN"})
    void short_or_non_numeric_ids_are_not_found() {
        client.signOn(ADMIN);
        for (String id : new String[] {"683580", "ABC"}) {
            assertThat(fetch(id).at("/message").asText()).as(id).isEqualTo("Transaction ID NOT found...");
        }
    }

    @Test
    @Covers({"BR-COTRN01C-01"})
    void non_numeric_batch_ids_are_found_as_typed() {
        jdbc.update("""
            INSERT INTO card_transaction (tran_id, type_cd, cat_cd, amount, card_num, orig_ts_text)
            VALUES ('2022-07-18000001', '01', 5, 1.00, (SELECT MIN(card_num) FROM card_xref), '2022-07-18 00:00:00.000000')
            """);
        client.signOn(ADMIN);
        JsonNode r = fetch("2022-07-18000001");
        assertThat(r.has("message")).isFalse();
        assertThat(r.at("/fields/catCd").asText()).isEqualTo("0005");
    }

    @Test
    @Covers({"TS-COTRN01C-07", "NAV-COTRN01C-04", "NAV-COTRN01C-08"})
    void back_returns_to_the_caller() {
        client.signOn(ADMIN);
        client.show("COTRN01C");
        assertThat(client.post("COTRN01C", "PF3", Map.of()).at("/navigate").asText()).isEqualTo("COMEN01C");

        client.show("COTRN00C");
        client.post("COTRN00C", "ENTER", Map.of("selections", List.of("S")));
        client.show("COTRN01C");
        assertThat(fetch("0000000001774260").has("message")).isFalse();
        assertThat(client.post("COTRN01C", "PF3", Map.of()).at("/navigate").asText()).isEqualTo("COTRN00C");
    }

    @Test
    @Covers({"TS-COTRN01C-08", "BR-COTRN01C-07", "NAV-COTRN01C-05"})
    void f4_clears() {
        client.signOn(ADMIN);
        fetch("0000000000683580");
        JsonNode r = client.post("COTRN01C", "PF4", Map.of("tranIdIn", "0000000000683580"));
        assertThat(r.at("/fields/tranIdIn").asText()).isEmpty();
        assertDetailsBlank(r);
        assertThat(r.has("message")).isFalse();
        assertThat(r.at("/focus").asText()).isEqualTo("tranIdIn");
    }

    @Test
    @Covers({"TS-COTRN01C-10", "NAV-COTRN01C-07"})
    void other_keys_are_invalid_and_blank_the_details() {
        client.signOn(ADMIN);
        fetch("0000000000683580");
        for (String k : new String[] {"PF7", "PF8", "PF1", "PA2"}) {
            JsonNode r = client.post("COTRN01C", k, Map.of("tranIdIn", "0000000000683580"));
            assertThat(r.at("/message").asText()).as(k).isEqualTo(CommonMessages.INVALID_KEY);
            assertThat(r.at("/fields/tranIdIn").asText()).isEqualTo("0000000000683580");
            assertDetailsBlank(r);
        }
    }

    @Test
    @Covers({"TS-COTRN01C-11", "NAV-COTRN01C-01", "C-ROLE"})
    void without_a_session_the_view_is_refused() {
        assertThat(client.showRaw("COTRN01C").getStatus()).isEqualTo(401);
        assertThat(client.turn("COTRN01C", "ENTER", Map.of("tranIdIn", "0000000000683580")).getStatus()).isEqualTo(401);
    }

    @Test
    @Covers({"Q-COTRN01C-07", "BR-COTRN01C-06", "C-SCOPE", "D-USER-ACCT"})
    void a_user_sees_only_transactions_of_linked_accounts() {
        String user = ownerOf(1);
        List<String> mine = inScopeIds(user);
        String outside = allIds().stream().filter(id -> !mine.contains(id)).findFirst().orElseThrow();
        client.signOn(user);
        assertThat(fetch(mine.get(0)).has("message")).isFalse();
        JsonNode r = fetch(outside);
        assertThat(r.at("/message").asText()).isEqualTo("Transaction ID NOT found...");
        assertDetailsBlank(r);
    }
}
