package com.carddemo.authorization;

import static org.assertj.core.api.Assertions.assertThat;

import com.carddemo.test.Covers;
import com.carddemo.test.ScreenClient;
import com.carddemo.test.TestApplication;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/** COPAUS1C authorization details and the fraud toggle with COPAUS2C, against the IMS sample. */
@SpringBootTest(classes = {TestApplication.class, AuthScreenTestSupport.Clocks.class},
    properties = "logging.level.com.carddemo.authorization.internal.PendingAuthDetails=DEBUG")
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class PendingAuthDetailTest extends AuthScreenTestSupport {

    static final String INVALID_KEY = "Invalid key pressed. Please see below...         ";

    Map<String, Object> fraudRow(String transactionId) {
        return jdbc.queryForMap("SELECT * FROM auth_fraud_report WHERE transaction_id = ?", transactionId);
    }

    @Test
    @Covers({"TS-COPAUS1C-01", "BR-COPAUS1C-01", "BR-COPAUS1C-02", "BR-COPAUS1C-03", "BR-COPAUS1C-04", "NAV-COPAUS1C-02",
        "C-MASK"})
    void the_selected_authorization_is_shown_with_every_field_and_a_masked_card() {
        JsonNode r = openDetail(admin, 1);
        JsonNode f = r.at("/fields");
        assertThat(r.has("message")).isFalse();
        assertThat(r.at("/focus").asText()).isEqualTo("cardNum");
        assertThat(f.at("/cardNum").asText()).isEqualTo("************7065");
        assertThat(f.at("/authDate").asText()).isEqualTo("11/16/23");
        assertThat(f.at("/authTime").asText()).isEqualTo("14:17:49");
        assertThat(f.at("/authResp").asText()).isEqualTo("A");
        assertThat(f.at("/respReason").asText()).isEqualTo("0000-APPROVED");
        assertThat(f.at("/authCode").asText()).isEqualTo("000000");
        assertThat(f.at("/amount").asText()).isEqualTo("        1.89");
        assertThat(f.at("/posEntryMode").asText()).isEqualTo("00");
        assertThat(f.at("/source").asText()).isEqualTo("102030");
        assertThat(f.at("/mccCode").asText()).isEqualTo("5442");
        assertThat(f.at("/cardExpiry").asText()).isEqualTo("11/23");
        assertThat(f.at("/authType").asText()).isEqualTo("0100");
        assertThat(f.at("/transactionId").asText()).isEqualTo(NEWEST_TXN);
        assertThat(f.at("/matchStatus").asText()).isEqualTo("P");
        assertThat(f.at("/fraudStatus").asText()).isEqualTo("-");
        assertThat(f.at("/merchantName").asText()).isEqualTo("Bestbuy.com");
        assertThat(f.at("/merchantId").asText()).isEqualTo("123501000675423");
        assertThat(f.at("/merchantCity").asText()).isEqualTo("Wilmington");
        assertThat(f.at("/merchantState").asText()).isEqualTo("DE");
        assertThat(f.at("/merchantZip").asText()).isEqualTo("19801");
        assertThat(r.toString()).doesNotContain(AuthFixtures.CARD_7);
    }

    @Test
    @Covers({"TS-COPAUS1C-02", "BR-COPAUS1C-05", "BR-COPAUS1C-06", "BR-COPAUS1C-07", "NAV-COPAUS1C-05", "TS-COPAUS2C-01",
        "BR-COPAUS2C-01", "BR-COPAUS2C-02", "BR-COPAUS2C-03", "BR-COPAUS2C-04", "NAV-COPAUS2C-01", "ERR-COPAUS2C-03",
        "Q-COPAUS2C-01", "Q-COPAUS2C-03", "Q-ENT-28", "Q-COPAUS1C-09"})
    void pf5_marks_fraud_in_both_tables_with_one_report_date(CapturedOutput out) {
        openDetail(admin, 1);
        Map<String, Object> before = detailRow(NEWEST_TXN);
        JsonNode r = detail(admin, "PF5");
        assertThat(r.at("/message").asText()).isEqualTo("AUTH MARKED FRAUD...");
        assertThat(r.at("/fields/fraudStatus").asText()).isEqualTo("F-03/05/24");
        assertThat(r.at("/fields/transactionId").asText()).isEqualTo(NEWEST_TXN);
        Map<String, Object> after = detailRow(NEWEST_TXN);
        assertThat(after.get("auth_fraud")).isEqualTo("F");
        assertThat(after.get("fraud_rpt_date").toString()).isEqualTo("2024-03-05");
        assertThat(fraudRows()).isEqualTo(1);
        Map<String, Object> fr = fraudRow(NEWEST_TXN);
        assertThat(fr.get("card_num")).isEqualTo(AuthFixtures.CARD_7);
        // AUTH_TS is the stored insert timestamp, and the unique authorization key is part of the row key.
        assertThat(((Timestamp) fr.get("auth_ts")).toLocalDateTime()).isEqualTo(((Timestamp) before.get("auth_ts")).toLocalDateTime())
            .isEqualTo(LocalDateTime.of(2023, 11, 16, 9, 17, 49, 523_000_000));
        assertThat(fr.get("auth_id")).isEqualTo(before.get("auth_id"));
        assertThat(fr.get("auth_fraud")).isEqualTo("F");
        assertThat(fr.get("fraud_rpt_date").toString()).isEqualTo("2024-03-05");
        assertThat(fr.get("acct_id")).isEqualTo(7L);
        assertThat(fr.get("cust_id")).isEqualTo(7);
        assertThat(fr.get("merchant_name")).isEqualTo("Bestbuy.com");
        assertThat(fr.get("merchant_id")).isEqualTo("123501000675423");
        assertThat(fr.get("processing_code")).isEqualTo("000000");
        assertThat((BigDecimal) fr.get("approved_amt")).isEqualByComparingTo("1.89");
        assertThat(fr.get("pos_entry_mode")).isEqualTo((short) 0);
        assertThat(fr.get("match_status")).isEqualTo("P");
        assertThat(out.getAll()).contains("RPT DT: 03/05/24");
    }

    @Test
    @Covers({"TS-COPAUS1C-03", "TS-COPAUS2C-02", "BR-COPAUS2C-05", "Q-COPAUS1C-03"})
    void pf5_again_removes_the_fraud_mark_and_updates_only_flag_and_date() {
        openDetail(admin, 1);
        detail(admin, "PF5");
        Map<String, Object> first = fraudRow(NEWEST_TXN);
        setToday(LocalDateTime.of(2024, 3, 6, 9, 0));
        // Moving the clock a day ends the sign-on session, so sign on again and reselect the same authorization.
        admin = signedOn("ADMIN001");
        assertThat(openDetail(admin, 1).at("/fields/fraudStatus").asText()).isEqualTo("F-03/05/24");
        JsonNode r = detail(admin, "PF5");
        assertThat(r.at("/message").asText()).isEqualTo("AUTH FRAUD REMOVED...");
        assertThat(r.at("/fields/fraudStatus").asText()).isEqualTo("R-03/06/24");
        assertThat(detailRow(NEWEST_TXN).get("auth_fraud")).isEqualTo("R");
        assertThat(fraudRows()).isEqualTo(1);
        Map<String, Object> second = fraudRow(NEWEST_TXN);
        assertThat(second.get("auth_fraud")).isEqualTo("R");
        assertThat(second.get("fraud_rpt_date").toString()).isEqualTo("2024-03-06");
        first.remove("auth_fraud");
        first.remove("fraud_rpt_date");
        second.remove("auth_fraud");
        second.remove("fraud_rpt_date");
        assertThat(second).isEqualTo(first);
        JsonNode again = detail(admin, "PF5");
        assertThat(again.at("/message").asText()).isEqualTo("AUTH MARKED FRAUD...");
        assertThat(again.at("/fields/fraudStatus").asText()).isEqualTo("F-03/06/24");
    }

    @Test
    @Covers({"TS-COPAUS2C-03", "BR-COPAUS2C-06"})
    void removing_a_mark_that_was_never_reported_inserts_an_r_row() {
        jdbc.update("UPDATE pending_auth_detail SET auth_fraud = 'F', fraud_rpt_date = '2024-03-01' WHERE transaction_id = ?",
            NEWEST_TXN);
        JsonNode shown = openDetail(admin, 1);
        assertThat(shown.at("/fields/fraudStatus").asText()).isEqualTo("F-03/01/24");
        JsonNode r = detail(admin, "PF5");
        assertThat(r.at("/message").asText()).isEqualTo("AUTH FRAUD REMOVED...");
        assertThat(fraudRow(NEWEST_TXN).get("auth_fraud")).isEqualTo("R");
        assertThat(fraudRows()).isEqualTo(1);
    }

    @Test
    @Covers({"TS-COPAUS1C-04", "ERR-COPAUS1C-05", "ERR-COPAUS2C-01", "Q-COPAUS1C-01", "Q-COPAUS2C-04"})
    void a_failed_fraud_insert_rolls_back_and_shows_the_stored_flag_with_the_db_error() {
        openDetail(admin, 1);
        Map<String, Object> before = detailRow(NEWEST_TXN);
        jdbc.execute("EXEC sp_rename 'auth_fraud_report', 'auth_fraud_report_off'");
        JsonNode r;
        try {
            r = detail(admin, "PF5");
        } finally {
            jdbc.execute("EXEC sp_rename 'auth_fraud_report_off', 'auth_fraud_report'");
        }
        String m = r.at("/message").asText();
        assertThat(m).startsWith(" SYSTEM ERROR DB2: CODE:-000208, STATE: ");
        assertThat(m.substring(" SYSTEM ERROR DB2: CODE:-000208, STATE: ".length())).hasSize(5).isEqualTo("S0002");
        assertThat(r.at("/fields/fraudStatus").asText()).isEqualTo("-");
        assertThat(r.at("/fields/transactionId").asText()).isEqualTo(NEWEST_TXN);
        assertThat(detailRow(NEWEST_TXN)).isEqualTo(before);
        assertThat(fraudRows()).isZero();
    }

    @Test
    @Covers({"ERR-COPAUS2C-02"})
    void a_failed_fraud_update_rolls_back_and_shows_the_update_error() {
        openDetail(admin, 1);
        detail(admin, "PF5");
        Map<String, Object> before = detailRow(NEWEST_TXN);
        jdbc.execute("CREATE TRIGGER trg_auth_fraud_report_block ON auth_fraud_report INSTEAD OF UPDATE AS THROW 51000, 'blocked', 1;");
        JsonNode r;
        try {
            r = detail(admin, "PF5");
        } finally {
            jdbc.execute("DROP TRIGGER trg_auth_fraud_report_block");
        }
        assertThat(r.at("/message").asText()).startsWith(" UPDT ERROR DB2: CODE:-051000, STATE: ");
        assertThat(r.at("/fields/fraudStatus").asText()).isEqualTo("F-03/05/24");
        assertThat(detailRow(NEWEST_TXN)).isEqualTo(before);
        assertThat(fraudRow(NEWEST_TXN).get("auth_fraud")).isEqualTo("F");
    }

    @Test
    @Covers({"TS-COPAUS1C-05", "BR-COPAUS1C-08", "NAV-COPAUS1C-06"})
    void pf8_shows_the_next_older_authorization_until_the_last() {
        openDetail(admin, 1);
        JsonNode r = detail(admin, "PF8");
        assertThat(r.has("message")).isFalse();
        assertThat(r.at("/fields/transactionId").asText()).isEqualTo(SECOND_TXN);
        assertThat(r.at("/fields/authTime").asText()).isEqualTo("14:17:41");
        assertThat(detail(admin, "ENTER").at("/fields/transactionId").asText()).isEqualTo(SECOND_TXN);

        List<String> all = allTxns(ACCOUNT_7);
        list("ENTER", ACCT7);
        for (int i = 0; i < 9; i++) {
            list("PF8", ACCT7);
        }
        assertThat(list("ENTER", ACCT7, 5, "S").at("/navigate").asText()).isEqualTo("COPAUS1C");
        JsonNode oldest = admin.show("COPAUS1C");
        assertThat(oldest.at("/fields/transactionId").asText()).isEqualTo(all.get(49));
        JsonNode end = detail(admin, "PF8");
        assertThat(end.at("/message").asText()).isEqualTo("Already at the last Authorization...");
        assertThat(end.at("/fields")).isEqualTo(oldest.at("/fields"));
    }

    @Test
    @Covers({"TS-COPAUS1C-06", "NAV-COPAUS1C-04"})
    void pf3_returns_to_page_one_of_the_account() {
        openDetail(admin, 3);
        assertThat(detail(admin, "PF3").at("/navigate").asText()).isEqualTo("COPAUS0C");
        JsonNode list = admin.show("COPAUS0C");
        assertThat(list.at("/fields/accountId").asText()).isEqualTo(ACCT7);
        assertThat(list.at("/fields/pageNum").asText()).isEqualTo("1");
        assertThat(txns(list)).containsExactlyElementsOf(rows(ACCOUNT_7, 1, 5));
    }

    @Test
    @Covers({"TS-COPAUS1C-07", "NAV-COPAUS1C-07", "NAV-COPAUS1C-03", "NAV-COPAUS1C-08"})
    void other_keys_redisplay_the_same_authorization_with_the_invalid_key_message() {
        JsonNode shown = openDetail(admin, 2);
        for (String key : List.of("PA1", "PF2", "PF7", "CLEAR")) {
            JsonNode r = detail(admin, key);
            assertThat(r.at("/message").asText()).as(key).isEqualTo(INVALID_KEY);
            assertThat(r.at("/fields")).as(key).isEqualTo(shown.at("/fields"));
        }
        JsonNode enter = detail(admin, "ENTER");
        assertThat(enter.has("message")).isFalse();
        assertThat(enter.at("/fields")).isEqualTo(shown.at("/fields"));
        assertThat(fraudRows()).isZero();
    }

    @Test
    @Covers({"TS-COPAUS1C-08", "VR-COPAUS1C-01", "Q-COPAUS1C-08"})
    void without_a_selection_the_screen_asks_for_one() {
        list("ENTER", ACCT7);
        admin.show("COPAUS0C");
        JsonNode r = admin.show("COPAUS1C");
        assertThat(r.at("/message").asText()).isEqualTo("Please select an authorization...");
        assertThat(r.at("/fields/cardNum").asText()).isEmpty();
        assertThat(r.at("/fields/transactionId").asText()).isEmpty();
        assertThat(detail(admin, "PF5").at("/message").asText()).isEqualTo("Please select an authorization...");
        assertThat(fraudRows()).isZero();
    }

    @Test
    @Covers({"TS-COPAUS1C-09", "NAV-COPAUS1C-01"})
    void entry_without_any_context_goes_to_the_list() {
        assertThat(admin.show("COPAUS1C").at("/navigate").asText()).isEqualTo("COPAUS0C");
    }

    @Test
    @Covers({"TS-COPAUS1C-10", "BR-COPAUS1C-03"})
    void decline_reasons_come_from_the_reason_table() {
        jdbc.update("UPDATE pending_auth_detail SET auth_resp_code = '05', auth_resp_reason = '4100' WHERE transaction_id = ?",
            NEWEST_TXN);
        JsonNode r = openDetail(admin, 1);
        assertThat(r.at("/fields/respReason").asText()).isEqualTo("4100-INSUFFICNT FUND");
        assertThat(r.at("/fields/authResp").asText()).isEqualTo("D");
        for (String[] c : List.of(new String[] {"1234", "9999-ERROR"}, new String[] {"4200", "4200-CARD NOT ACTIVE"},
            new String[] {"5200", "5200-MERCHANT FRAUD"}, new String[] {"9000", "9000-UNKNOWN"})) {
            jdbc.update("UPDATE pending_auth_detail SET auth_resp_reason = ? WHERE transaction_id = ?", c[0], NEWEST_TXN);
            assertThat(detail(admin, "ENTER").at("/fields/respReason").asText()).isEqualTo(c[1]);
        }
    }

    @Test
    @Covers({"Q-COPAUS1C-02", "ERR-COPAUS1C-08"})
    void a_vanished_authorization_shows_nothing_and_pf5_does_nothing() {
        openDetail(admin, 1);
        jdbc.update("DELETE FROM pending_auth_detail WHERE transaction_id = ?", NEWEST_TXN);
        for (String key : List.of("ENTER", "PF5", "PF8")) {
            JsonNode r = detail(admin, key);
            assertThat(r.at("/message").asText()).as(key).isEqualTo("Authorization not found...");
            assertThat(r.at("/fields/cardNum").asText()).isEmpty();
        }
        assertThat(fraudRows()).isZero();
        openDetail(admin, 1);
        jdbc.update("DELETE FROM pending_auth_summary WHERE account_id = 7");
        assertThat(detail(admin, "ENTER").at("/message").asText()).isEqualTo("Authorization not found...");
        assertThat(detail(admin, "PF5").at("/message").asText()).isEqualTo("Authorization not found...");
        assertThat(fraudRows()).isZero();
    }

    @Test
    @Covers({"A-2", "C-SCOPE"})
    void a_regular_user_works_on_the_authorizations_of_a_linked_account() {
        ScreenClient owner = signedOn("USER0002");
        JsonNode r = openDetail(owner, 1);
        assertThat(r.at("/fields/transactionId").asText()).isEqualTo(NEWEST_TXN);
        assertThat(detail(owner, "PF5").at("/message").asText()).isEqualTo("AUTH MARKED FRAUD...");
        assertThat(detailRow(NEWEST_TXN).get("fraud_rpt_date").toString()).isEqualTo(LocalDate.of(2024, 3, 5).toString());
    }
}
