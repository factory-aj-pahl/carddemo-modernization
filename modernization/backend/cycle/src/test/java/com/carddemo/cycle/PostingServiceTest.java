package com.carddemo.cycle;

import static com.carddemo.test.DailyRecords.withAmount;
import static com.carddemo.test.DailyRecords.withCard;
import static com.carddemo.test.DailyRecords.withOrigTs;
import static com.carddemo.test.DailyRecords.withTranId;
import static com.carddemo.test.DailyRecords.withType;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.carddemo.account.api.AccountPostings;
import com.carddemo.account.api.PostingAccount;
import com.carddemo.common.batch.BatchAbend;
import com.carddemo.common.batch.JobLog;
import com.carddemo.cycle.api.DailyPosting;
import com.carddemo.cycle.api.PostingOutcome;
import com.carddemo.test.Covers;
import com.carddemo.test.DailyRecords;
import com.carddemo.test.SampleData;
import com.carddemo.test.TestApplication;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/** CBTRN02C validation and posting of single records (spec/programs/CBTRN02C.md, ledger Q-CBTRN02C rows). */
@SpringBootTest(classes = {TestApplication.class, PostingServiceTest.Fixtures.class})
class PostingServiceTest {

    static final LocalDate DATE = LocalDate.of(2022, 6, 10);
    static final long RUN = 1;

    @TestConfiguration
    static class Fixtures {
        static volatile boolean failAccountUpdate;

        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(Instant.parse("2022-07-18T23:05:41.376Z"), ZoneId.of("UTC"));
        }

        @Bean
        static BeanPostProcessor accountUpdateFault() {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String name) {
                    if (bean instanceof AccountPostings real) {
                        return new AccountPostings() {
                            @Override
                            public Optional<PostingAccount> lockForPosting(long id) {
                                return real.lockForPosting(id);
                            }

                            @Override
                            public boolean applyPosting(long id, BigDecimal amount) {
                                return !failAccountUpdate && real.applyPosting(id, amount);
                            }
                        };
                    }
                    return bean;
                }
            };
        }
    }

    @Autowired
    DailyPosting posting;
    @Autowired
    SampleData sample;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    TransactionTemplate tx;
    @Autowired
    JobLog log;

    @BeforeEach
    void load() {
        sample.load();
        log.startCapture();
    }

    @AfterEach
    void reset() {
        Fixtures.failAccountUpdate = false;
    }

    PostingOutcome post(String rec) {
        return tx.execute(s -> posting.post(rec, RUN, DATE));
    }

    Map<String, Object> account(long id) {
        return jdbc.queryForMap("SELECT current_balance, curr_cycle_credit, curr_cycle_debit FROM account WHERE account_id = ?", id);
    }

    BigDecimal categoryBalance(long acct, String type, int cat) {
        List<BigDecimal> b = jdbc.queryForList("SELECT balance FROM tran_cat_balance WHERE account_id = ? AND type_cd = ? AND cat_cd = ?",
            BigDecimal.class, acct, type, cat);
        return b.isEmpty() ? null : b.get(0);
    }

    String cardOf(long acct) {
        return jdbc.queryForObject("SELECT MIN(card_num) FROM card_xref WHERE account_id = ?", String.class, acct);
    }

    @Test
    @Covers({"TS-CBTRN02C-03", "BR-CBTRN02C-02", "BR-CBTRN02C-09", "VR-CBTRN02C-03"})
    void overlimit_transaction_is_rejected_with_the_daily_record_and_trailer() {
        String rec = DailyRecords.sample("0000000040455859");
        Map<String, Object> before = account(37);
        PostingOutcome o = post(rec);
        assertThat(o.reasonCode()).isEqualTo(102);
        assertThat(posting.rejectRecords(RUN)).containsExactly(rec + "0102OVERLIMIT TRANSACTION" + " ".repeat(55));
        assertThat(account(37)).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM card_transaction", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT business_date FROM posting_reject", LocalDate.class)).isEqualTo(DATE);
    }

    @Test
    @Covers({"TS-CBTRN02C-04", "VR-CBTRN02C-01"})
    void unknown_card_is_rejected_with_reason_100() {
        PostingOutcome o = post(withCard(DailyRecords.sample().get(0), "9999999999999999"));
        assertThat(o).isEqualTo(new PostingOutcome(100, "INVALID CARD NUMBER FOUND"));
        assertThat(posting.rejectRecords(RUN).get(0)).endsWith("0100INVALID CARD NUMBER FOUND" + " ".repeat(51));
    }

    @Test
    @Covers({"TS-CBTRN02C-05", "VR-CBTRN02C-02"})
    void card_whose_account_does_not_exist_is_rejected_with_reason_101() {
        jdbc.update("INSERT INTO card_xref (card_num, customer_id, account_id) VALUES ('1111111111111111', 1, 99999)");
        PostingOutcome o = post(withCard(DailyRecords.sample().get(0), "1111111111111111"));
        assertThat(o).isEqualTo(new PostingOutcome(101, "ACCOUNT RECORD NOT FOUND"));
    }

    @Test
    @Covers({"TS-CBTRN02C-06", "BR-CBTRN02C-03", "Q-CBTRN02C-03", "VR-CBTRN02C-04"})
    void expiry_check_overrides_the_limit_check_and_compares_dates_as_text() {
        jdbc.update("UPDATE account SET expiration_date = '2022-06-01', credit_limit = 10 WHERE account_id = 7");
        String rec = withOrigTs(DailyRecords.sample("0000000000683580"), "2022-06-10 19:27:53.000000");
        assertThat(post(rec).reasonCode()).isEqualTo(103);
        jdbc.update("UPDATE account SET credit_limit = 100000 WHERE account_id = 7");
        String sameDay = withTranId(withOrigTs(rec, "2022-06-01 08:00:00.000000"), "0000000000000001");
        assertThat(post(sameDay).posted()).isTrue();
    }

    @Test
    @Covers({"TS-CBTRN02C-07", "Q-CBTRN02C-02", "Q-ENT-09"})
    void limit_test_adds_returns_to_the_used_credit() {
        jdbc.update("UPDATE account SET credit_limit = 150, curr_cycle_credit = 100, curr_cycle_debit = -50 WHERE account_id = 7");
        String rec = withAmount(DailyRecords.sample("0000000000683580"), "10.00");
        assertThat(post(rec).reasonCode()).isEqualTo(102);
        jdbc.update("UPDATE account SET credit_limit = 160 WHERE account_id = 7");
        assertThat(post(rec).posted()).isTrue();
    }

    @Test
    @Covers({"Q-CBTRN02C-11"})
    void limit_sum_keeps_digits_above_nine_integer_places() {
        jdbc.update("UPDATE account SET credit_limit = 500, curr_cycle_credit = 1000000000.00, curr_cycle_debit = 0 WHERE account_id = 7");
        assertThat(post(withAmount(DailyRecords.sample("0000000000683580"), "10.00")).reasonCode()).isEqualTo(102);
    }

    @Test
    @Covers({"BR-CBTRN02C-04", "BR-CBTRN02C-05", "BR-CBTRN02C-06", "BR-CBTRN02C-07", "Q-CBTRN02C-10"})
    void valid_purchase_updates_category_balance_account_and_writes_the_transaction() {
        String rec = DailyRecords.sample("0000000000683580");
        Map<String, Object> before = account(7);
        assertThat(post(rec).posted()).isTrue();
        Map<String, Object> after = account(7);
        assertThat((BigDecimal) after.get("current_balance"))
            .isEqualByComparingTo(((BigDecimal) before.get("current_balance")).add(new BigDecimal("504.77")));
        assertThat((BigDecimal) after.get("curr_cycle_credit"))
            .isEqualByComparingTo(((BigDecimal) before.get("curr_cycle_credit")).add(new BigDecimal("504.77")));
        assertThat((BigDecimal) after.get("curr_cycle_debit")).isEqualByComparingTo((BigDecimal) before.get("curr_cycle_debit"));
        assertThat(categoryBalance(7, "01", 1)).isEqualByComparingTo("504.77");
        Map<String, Object> t = jdbc.queryForMap("SELECT * FROM card_transaction WHERE tran_id = '0000000000683580'");
        assertThat(t.get("proc_ts_text")).isEqualTo("2022-07-18-23.05.41.370000");
        assertThat(t.get("orig_ts_text")).isEqualTo("2022-06-10 19:27:53.000000");
        assertThat(t.get("description")).isEqualTo("Purchase at Abshire-Lowe");
        assertThat(t.get("card_num")).isEqualTo(cardOf(7));
        assertThat(log.captured()).isEmpty();
    }

    @Test
    @Covers({"BR-CBTRN02C-06", "BR-CBTRN02C-07", "Q-CBTRN02C-12"})
    void negative_amount_goes_to_cycle_debit_and_creates_a_missing_category_balance() {
        String rec = withType(withAmount(DailyRecords.sample("0000000000683580"), "-25.50"), "03", 1);
        assertThat(post(rec).posted()).isTrue();
        Map<String, Object> after = account(7);
        assertThat((BigDecimal) after.get("curr_cycle_debit")).isEqualByComparingTo("-25.50");
        assertThat(categoryBalance(7, "03", 1)).isEqualByComparingTo("-25.50");
        assertThat(jdbc.queryForObject("SELECT legacy_filler FROM tran_cat_balance WHERE account_id = 7 AND type_cd = '03'", String.class))
            .isEmpty();
        assertThat(log.captured()).containsExactly("TCATBAL record not found for key : 00000000007030001.. Creating.");
    }

    @Test
    @Covers({"VR-CBTRN02C-05"})
    void type_category_amount_and_card_or_account_status_are_not_validated() {
        jdbc.update("UPDATE account SET active_status = 'N' WHERE account_id = 7");
        jdbc.update("UPDATE card SET active_status = 'N', expiration_date = '2000-01-01' WHERE account_id = 7");
        String rec = withType(withAmount(DailyRecords.sample("0000000000683580"), "0.00"), "99", 9999);
        assertThat(post(rec).posted()).isTrue();
        assertThat(categoryBalance(7, "99", 9999)).isEqualByComparingTo("0.00");
        assertThat(jdbc.queryForObject("SELECT type_cd FROM card_transaction WHERE tran_id = '0000000000683580'", String.class))
            .isEqualTo("99");
    }

    @Test
    @Covers({"Q-CBTRN02C-04", "ERR-CBTRN02C-05"})
    void failed_account_update_rolls_back_the_record_and_rejects_it_with_109() {
        Fixtures.failAccountUpdate = true;
        String rec = withType(DailyRecords.sample("0000000000683580"), "03", 1);
        PostingOutcome o = post(rec);
        assertThat(o).isEqualTo(new PostingOutcome(109, "ACCOUNT RECORD NOT FOUND"));
        assertThat(categoryBalance(7, "03", 1)).isNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM card_transaction", Integer.class)).isZero();
        assertThat(posting.rejectRecords(RUN)).containsExactly(rec + "0109ACCOUNT RECORD NOT FOUND" + " ".repeat(52));
    }

    @Test
    @Covers({"ERR-CBTRN02C-06", "ERR-CBTRN02C-09", "Q-ENT-07"})
    void duplicate_transaction_id_abends_with_status_22() {
        String rec = DailyRecords.sample("0000000000683580");
        post(rec);
        assertThatThrownBy(() -> post(rec)).isInstanceOfSatisfying(BatchAbend.class, a -> assertThat(a.sysoutLines())
            .containsExactly("ERROR WRITING TO TRANSACTION FILE", "FILE STATUS IS: NNNN0022"));
        assertThat(categoryBalance(7, "01", 1)).isEqualByComparingTo("504.77");
    }
}
