package com.carddemo.authorization;

import static org.assertj.core.api.Assertions.assertThat;

import com.carddemo.test.Covers;
import com.carddemo.test.ScreenClient;
import com.carddemo.test.TestApplication;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;

/** COPAUS0C View Authorizations against the IMS sample (account 7: 50 authorizations, account 2: none). */
@SpringBootTest(classes = {TestApplication.class, AuthScreenTestSupport.Clocks.class})
@AutoConfigureMockMvc
class PendingAuthListTest extends AuthScreenTestSupport {

    static final String INVALID_KEY = "Invalid key pressed. Please see below...         ";
    static final String TOP = "You are already at the top of the page...";
    static final String BOTTOM = "You are already at the bottom of the page...";

    JsonNode pages(int presses) {
        JsonNode r = list("ENTER", ACCT7);
        for (int i = 0; i < presses; i++) {
            r = list("PF8", ACCT7);
        }
        return r;
    }

    @Test
    @Covers({"TS-COPAUS0C-01", "BR-COPAUS0C-01", "BR-COPAUS0C-04", "BR-COPAUS0C-05", "BR-COPAUS0C-06", "BR-COPAUS0C-07",
        "BR-COPAUS0C-08", "BR-COPAUS0C-09", "NAV-COPAUS0C-03", "Q-COPAUS0C-05", "Q-COPAUS0C-10", "Q-COPAUS0C-08", "Q-COPAUS0C-06"})
    void account_seven_shows_the_header_summary_and_the_five_newest_authorizations() {
        JsonNode r = list("ENTER", ACCT7);
        JsonNode f = r.at("/fields");
        assertThat(r.has("message")).isFalse();
        assertThat(r.at("/focus").asText()).isEqualTo("accountId");
        assertThat(f.at("/accountId").asText()).isEqualTo(ACCT7);
        assertThat(f.at("/name").asText()).isEqualTo("Cooper D Mayert");
        assertThat(f.at("/customerId").asText()).isEqualTo("000000007");
        assertThat(f.at("/address1").asText()).isEqualTo("6490 Zakary Locks,Apt. 76");
        assertThat(f.at("/address2").asText()).isEqualTo("Madieport,AL,34206");
        assertThat(f.at("/phone").asText()).isEqualTo("(698)282-4096");
        assertThat(f.at("/accountStatus").asText())
            .isEqualTo(jdbc.queryForObject("SELECT active_status FROM account WHERE account_id = 7", String.class)).isNotBlank();
        assertThat(f.at("/creditLimit").asText()).isEqualTo("     2065.00");
        assertThat(f.at("/cashLimit").asText()).isEqualTo("   264.00");
        // Q-COPAUS0C-06: counts without leading zeros, also when a summary exists.
        assertThat(f.at("/approvedCount").asText()).isEqualTo("50");
        assertThat(f.at("/declinedCount").asText()).isEqualTo("0");
        assertThat(f.at("/creditBalance").asText()).isEqualTo("       74.79");
        assertThat(f.at("/cashBalance").asText()).isEqualTo("     0.00");
        assertThat(f.at("/approvedAmount").asText()).isEqualTo("    74.79");
        assertThat(f.at("/declinedAmount").asText()).isEqualTo("     0.00");
        assertThat(f.at("/pageNum").asText()).isEqualTo("1");
        assertThat(txns(r)).containsExactlyElementsOf(rows(ACCOUNT_7, 1, 5));
        JsonNode row = f.at("/rows/0");
        assertThat(row.at("/transactionId").asText()).isEqualTo(NEWEST_TXN);
        assertThat(row.at("/date").asText()).isEqualTo("11/16/23");
        assertThat(row.at("/time").asText()).isEqualTo("14:17:49");
        assertThat(row.at("/type").asText()).isEqualTo("0100");
        assertThat(row.at("/approval").asText()).isEqualTo("A");
        assertThat(row.at("/status").asText()).isEqualTo("P");
        assertThat(row.at("/amount").asText()).isEqualTo("        1.89");
        assertThat(row.at("/selectable").asBoolean()).isTrue();
        assertThat(f.at("/rows/1/transactionId").asText()).isEqualTo(SECOND_TXN);
        f.at("/rows").forEach(x -> {
            assertThat(x.at("/approval").asText()).isEqualTo("A");
            assertThat(x.at("/status").asText()).isEqualTo("P");
        });
        assertThat(r.toString()).doesNotContain(AuthFixtures.CARD_7);
    }

    @Test
    @Covers({"TS-COPAUS0C-02", "VR-COPAUS0C-01", "ERR-COPAUS0C-09"})
    void an_empty_account_id_asks_for_one_and_reads_nothing() {
        list("ENTER", ACCT7);
        JsonNode r = list("ENTER", "");
        assertThat(r.at("/message").asText()).isEqualTo("Please enter Acct Id...");
        assertThat(r.at("/messageColor").asText()).isEqualTo("RED");
        assertThat(r.at("/focus").asText()).isEqualTo("accountId");
        assertThat(r.at("/fields/name").asText()).isEmpty();
        assertThat(txns(r)).containsOnly("");
        JsonNode spaces = list("ENTER", "           ");
        assertThat(spaces.at("/message").asText()).isEqualTo("Please enter Acct Id...");
        // A turn without input fields (an unreadable map) is the same empty input.
        JsonNode none = admin.post("COPAUS0C", "ENTER", Map.of());
        assertThat(none.at("/message").asText()).isEqualTo("Please enter Acct Id...");
    }

    @Test
    @Covers({"TS-COPAUS0C-03", "VR-COPAUS0C-02"})
    void an_account_id_must_be_eleven_digits() {
        for (String bad : List.of("7", "0000000000A", "-0000000007", "0000000 007")) {
            JsonNode r = list("ENTER", bad);
            assertThat(r.at("/message").asText()).as(bad).isEqualTo("Acct Id must be Numeric ...");
            assertThat(r.at("/fields/accountId").asText()).isEmpty();
            assertThat(txns(r)).containsOnly("");
        }
    }

    @Test
    @Covers({"TS-COPAUS0C-04", "VR-COPAUS0C-03", "BR-COPAUS0C-03"})
    void an_invalid_selection_code_rebuilds_page_one_with_a_message() {
        list("ENTER", ACCT7);
        JsonNode r = list("ENTER", ACCT7, 2, "X");
        assertThat(r.has("navigate")).isFalse();
        assertThat(r.at("/message").asText()).isEqualTo("Invalid selection. Valid value is S");
        assertThat(r.at("/focus").asText()).isEqualTo("accountId");
        assertThat(txns(r)).containsExactlyElementsOf(rows(ACCOUNT_7, 1, 5));
        assertThat(r.at("/fields/rows/1/sel").asText()).isEmpty();
        list("PF8", ACCT7);
        JsonNode enter = list("ENTER", ACCT7);
        assertThat(enter.at("/fields/pageNum").asText()).isEqualTo("1");
        assertThat(txns(enter)).containsExactlyElementsOf(rows(ACCOUNT_7, 1, 5));
        JsonNode firstOnly = list("ENTER", ACCT7, 1, "X", 2, "S");
        assertThat(firstOnly.at("/message").asText()).isEqualTo("Invalid selection. Valid value is S");
    }

    @Test
    @Covers({"TS-COPAUS0C-05", "NAV-COPAUS0C-04", "VR-COPAUS0C-04"})
    void s_on_a_filled_row_opens_its_details_and_a_select_on_an_empty_row_is_ignored() {
        list("ENTER", ACCT7);
        JsonNode nav = list("ENTER", ACCT7, 1, "S");
        assertThat(nav.at("/navigate").asText()).isEqualTo("COPAUS1C");
        assertThat(admin.show("COPAUS1C").at("/fields/transactionId").asText()).isEqualTo(NEWEST_TXN);
        list("ENTER", ACCT7);
        assertThat(list("ENTER", ACCT7, 3, "s").at("/navigate").asText()).isEqualTo("COPAUS1C");
        assertThat(admin.show("COPAUS1C").at("/fields/transactionId").asText()).isEqualTo(rows(ACCOUNT_7, 1, 5).get(2));

        // Account 5 has one authorization: rows 2-5 are empty and protected.
        JsonNode five = list("ENTER", "00000000005");
        assertThat(five.at("/fields/rows/1/selectable").asBoolean()).isFalse();
        JsonNode ignored = list("ENTER", "00000000005", 2, "S");
        assertThat(ignored.has("navigate")).isFalse();
        assertThat(ignored.has("message")).isFalse();
        assertThat(txns(ignored).get(0)).isNotEmpty();
    }

    @Test
    @Covers({"TS-COPAUS0C-06", "NAV-COPAUS0C-07", "BR-COPAUS0C-10", "NAV-COPAUS0C-09"})
    void pf8_pages_forward_to_the_last_page_then_says_bottom() {
        JsonNode p2 = pages(1);
        assertThat(p2.at("/fields/pageNum").asText()).isEqualTo("2");
        assertThat(txns(p2)).containsExactlyElementsOf(rows(ACCOUNT_7, 6, 10));
        assertThat(p2.has("message")).isFalse();
        assertThat(p2.at("/fields/name").asText()).isEqualTo("Cooper D Mayert");
        JsonNode p10 = pages(9);
        assertThat(p10.at("/fields/pageNum").asText()).isEqualTo("10");
        assertThat(txns(p10)).containsExactlyElementsOf(rows(ACCOUNT_7, 46, 50));
        assertThat(p10.has("message")).isFalse();
        JsonNode bottom = list("PF8", ACCT7);
        assertThat(bottom.at("/message").asText()).isEqualTo(BOTTOM);
        assertThat(bottom.at("/fields/pageNum").asText()).isEqualTo("10");
        assertThat(txns(bottom)).containsExactlyElementsOf(rows(ACCOUNT_7, 46, 50));
    }

    @Test
    @Covers({"TS-COPAUS0C-07", "NAV-COPAUS0C-06"})
    void pf7_on_page_one_says_top() {
        list("ENTER", ACCT7);
        JsonNode r = list("PF7", ACCT7);
        assertThat(r.at("/message").asText()).isEqualTo(TOP);
        assertThat(txns(r)).containsExactlyElementsOf(rows(ACCOUNT_7, 1, 5));
    }

    @Test
    @Covers({"TS-COPAUS0C-08", "Q-COPAUS0C-01"})
    void pf7_moves_back_one_page_at_a_time_down_to_page_one() {
        pages(2);
        JsonNode back1 = list("PF7", ACCT7);
        assertThat(back1.at("/fields/pageNum").asText()).isEqualTo("2");
        assertThat(txns(back1)).containsExactlyElementsOf(rows(ACCOUNT_7, 6, 10));
        JsonNode back2 = list("PF7", ACCT7);
        assertThat(back2.at("/fields/pageNum").asText()).isEqualTo("1");
        assertThat(txns(back2)).containsExactlyElementsOf(rows(ACCOUNT_7, 1, 5));
        assertThat(back2.has("message")).isFalse();
        JsonNode top = list("PF7", ACCT7);
        assertThat(top.at("/message").asText()).isEqualTo(TOP);
        JsonNode forward = list("PF8", ACCT7);
        assertThat(txns(forward)).containsExactlyElementsOf(rows(ACCOUNT_7, 6, 10));
    }

    @Test
    @Covers({"Q-COPAUS0C-02"})
    void paging_has_no_page_limit() {
        // Account 13 (58 authorizations) gets two older copies of each: 174 rows, 35 pages.
        String cols = String.join(", ", jdbc.queryForList("SELECT COLUMN_NAME FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME = "
            + "'pending_auth_detail' AND COLUMN_NAME NOT IN ('auth_id', 'row_version', 'auth_ts') ORDER BY ORDINAL_POSITION",
            String.class));
        Long maxId = jdbc.queryForObject("SELECT MAX(auth_id) FROM pending_auth_detail", Long.class);
        for (int k = 1; k <= 2; k++) {
            jdbc.update("INSERT INTO pending_auth_detail (auth_ts, " + cols + ") SELECT DATEADD(year, -" + k + ", auth_ts), "
                + cols + " FROM pending_auth_detail WHERE account_id = 13 AND auth_id <= ?", maxId);
        }
        assertThat(allTxns(13)).hasSize(174);
        JsonNode r = list("ENTER", "00000000013");
        for (int i = 1; i < 35; i++) {
            r = list("PF8", "00000000013");
        }
        assertThat(r.at("/fields/pageNum").asText()).isEqualTo("35");
        assertThat(txns(r)).containsExactlyElementsOf(rows(13, 171, 175));
        assertThat(list("PF8", "00000000013").at("/message").asText()).isEqualTo(BOTTOM);
        JsonNode back = list("PF7", "00000000013");
        assertThat(back.at("/fields/pageNum").asText()).isEqualTo("34");
        assertThat(txns(back)).containsExactlyElementsOf(rows(13, 166, 170));
        assertThat(back.at("/fields/name").asText()).isNotEmpty();
    }

    @Test
    @Covers({"TS-COPAUS0C-09", "BR-COPAUS0C-06", "BR-COPAUS0C-11", "Q-COPAUS0C-06"})
    void an_account_without_a_summary_shows_zero_amounts_and_no_rows() {
        JsonNode r = list("ENTER", "00000000002");
        JsonNode f = r.at("/fields");
        assertThat(r.has("message")).isFalse();
        assertThat(f.at("/customerId").asText()).isEqualTo("000000002");
        assertThat(f.at("/creditLimit").asText()).isEqualTo("     6130.00");
        assertThat(f.at("/cashLimit").asText()).isEqualTo("  5448.00");
        assertThat(f.at("/approvedCount").asText()).isEqualTo("0");
        assertThat(f.at("/declinedCount").asText()).isEqualTo("0");
        assertThat(f.at("/creditBalance").asText()).isEqualTo("        0.00");
        assertThat(f.at("/cashBalance").asText()).isEqualTo("     0.00");
        assertThat(f.at("/approvedAmount").asText()).isEqualTo("     0.00");
        assertThat(f.at("/declinedAmount").asText()).isEqualTo("     0.00");
        assertThat(f.at("/pageNum").asText()).isEqualTo("0");
        assertThat(txns(r)).containsOnly("");
        f.at("/rows").forEach(x -> assertThat(x.at("/selectable").asBoolean()).isFalse());
        assertThat(list("PF8", "00000000002").at("/message").asText()).isEqualTo(BOTTOM);

        // Large values keep every digit.
        jdbc.update("UPDATE pending_auth_summary SET cash_balance = 1234567.89, approved_count = 1234, credit_balance = -5.5 "
            + "WHERE account_id = 7");
        JsonNode big = list("ENTER", ACCT7);
        assertThat(big.at("/fields/cashBalance").asText()).isEqualTo(" 1234567.89");
        assertThat(big.at("/fields/approvedCount").asText()).isEqualTo("1234");
        assertThat(big.at("/fields/creditBalance").asText()).isEqualTo("-       5.50");
    }

    @Test
    @Covers({"TS-COPAUS0C-10", "ERR-COPAUS0C-01", "Q-COPAUS0C-04"})
    void an_account_missing_from_the_cross_reference_stops_with_the_xref_message() {
        list("ENTER", ACCT7);
        JsonNode r = list("ENTER", "99999999999");
        assertThat(r.at("/message").asText())
            .startsWith("Account:99999999999 not found in XREF file. Resp:000000013")
            .isEqualTo("Account:99999999999 not found in XREF file. Resp:000000013 Reas:000000080");
        assertThat(r.at("/fields/accountId").asText()).isEqualTo("99999999999");
        assertThat(r.at("/fields/name").asText()).isEmpty();
        assertThat(r.at("/fields/creditLimit").asText()).isEmpty();
        assertThat(r.at("/fields/approvedCount").asText()).isEmpty();
        assertThat(txns(r)).containsOnly("");
        assertThat(r.toString()).doesNotContain("Cooper");
    }

    @Test
    @Covers({"TS-COPAUS0C-11", "NAV-COPAUS0C-05"})
    void pf3_returns_to_the_main_menu() {
        list("ENTER", ACCT7);
        assertThat(list("PF3", ACCT7).at("/navigate").asText()).isEqualTo("COMEN01C");
        assertThat(admin.post("COPAUS0C", "PF3", Map.of()).at("/navigate").asText()).isEqualTo("COMEN01C");
    }

    @Test
    @Covers({"TS-COPAUS0C-12", "NAV-COPAUS0C-08", "Q-COPAUS0C-09"})
    void any_other_key_redisplays_the_whole_screen_with_the_invalid_key_message() {
        JsonNode shown = pages(1);
        for (String key : List.of("PF5", "PF1", "PA1", "CLEAR", "PF12")) {
            JsonNode r = list(key, ACCT7);
            assertThat(r.at("/message").asText()).as(key).isEqualTo(INVALID_KEY);
            assertThat(r.at("/fields")).as(key).isEqualTo(shown.at("/fields"));
        }
        assertThat(txns(list("PF8", ACCT7))).containsExactlyElementsOf(rows(ACCOUNT_7, 11, 15));
    }

    @Test
    @Covers({"TS-COPAUS0C-13", "NAV-COPAUS0C-02", "BR-COPAUS0C-02", "NAV-COPAUS0C-01", "Q-COPAUS0C-03"})
    void entry_opens_on_the_account_in_context_or_shows_an_empty_prompt() {
        JsonNode first = admin.show("COPAUS0C");
        assertThat(first.has("message")).isFalse();
        assertThat(first.at("/focus").asText()).isEqualTo("accountId");
        assertThat(first.at("/fields/accountId").asText()).isEmpty();
        assertThat(first.at("/fields/name").asText()).isEmpty();
        assertThat(txns(first)).containsOnly("");
        list("ENTER", ACCT7);
        list("PF8", ACCT7);
        JsonNode again = admin.show("COPAUS0C");
        assertThat(again.at("/fields/accountId").asText()).isEqualTo(ACCT7);
        assertThat(again.at("/fields/name").asText()).isEqualTo("Cooper D Mayert");
        assertThat(again.at("/fields/pageNum").asText()).isEqualTo("1");
        assertThat(txns(again)).containsExactlyElementsOf(rows(ACCOUNT_7, 1, 5));
        assertThat(signedOn("ADMIN001").show("COPAUS0C").at("/fields/accountId").asText()).isEmpty();
    }

    @Test
    @Covers({"A-2", "C-SCOPE"})
    void a_regular_user_sees_linked_accounts_only() {
        ScreenClient owner = signedOn("USER0002");
        JsonNode mine = list(owner, "ENTER", ACCT7);
        assertThat(mine.has("message")).isFalse();
        assertThat(txns(mine)).containsExactlyElementsOf(rows(ACCOUNT_7, 1, 5));
        ScreenClient other = signedOn("USER0001");
        JsonNode r = list(other, "ENTER", ACCT7);
        assertThat(r.at("/message").asText()).isEqualTo("Account:00000000007 not found in XREF file. Resp:000000013 Reas:000000080");
        assertThat(txns(r)).containsOnly("");
        assertThat(r.toString()).doesNotContain("Cooper");
    }
}
