package com.carddemo.billpay;

import static org.assertj.core.api.Assertions.assertThat;

import com.carddemo.account.api.AccountBalances;
import com.carddemo.billpay.internal.BillPaymentService;
import com.carddemo.common.security.CurrentUser;
import com.carddemo.common.security.Role;
import com.carddemo.test.Covers;
import com.carddemo.test.SampleData;
import com.carddemo.test.ScreenClient;
import com.carddemo.test.TestApplication;
import com.carddemo.transaction.api.NewTransaction;
import com.carddemo.transaction.api.TransactionLedger;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/** Failure atomicity (Q-COBIL00C-02) with injected step failures, and concurrent payments (Q-COBIL00C-01). */
@SpringBootTest(classes = {TestApplication.class, BillPaymentAtomicityTest.Faults.class})
@AutoConfigureMockMvc
class BillPaymentAtomicityTest {

    /** Wraps the published APIs so a test can make one step fail. */
    @TestConfiguration
    static class Faults {
        static volatile boolean failInsert;
        static volatile boolean failUpdate;
        static volatile boolean failNextId;

        @Bean
        static BeanPostProcessor faultInjector() {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String name) {
                    if (bean instanceof TransactionLedger real) {
                        return new TransactionLedger() {
                            @Override
                            public String nextOnlineTransactionId() {
                                if (failNextId) {
                                    throw new DataAccessResourceFailureException("injected");
                                }
                                return real.nextOnlineTransactionId();
                            }

                            @Override
                            public void insert(NewTransaction t) {
                                if (failInsert) {
                                    throw new DataAccessResourceFailureException("injected");
                                }
                                real.insert(t);
                            }
                        };
                    }
                    if (bean instanceof AccountBalances real) {
                        return new AccountBalances() {
                            @Override
                            public Optional<BigDecimal> currentBalance(long id) {
                                return real.currentBalance(id);
                            }

                            @Override
                            public Optional<BigDecimal> lockCurrentBalance(long id) {
                                return real.lockCurrentBalance(id);
                            }

                            @Override
                            public boolean updateCurrentBalance(long id, BigDecimal b) {
                                if (failUpdate) {
                                    throw new DataAccessResourceFailureException("injected");
                                }
                                return real.updateCurrentBalance(id, b);
                            }
                        };
                    }
                    return bean;
                }
            };
        }
    }

    @Autowired
    MockMvc mvc;
    @Autowired
    SampleData sample;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    BillPaymentService payments;

    @BeforeEach
    void load() {
        sample.load();
    }

    @AfterEach
    void reset() {
        Faults.failInsert = false;
        Faults.failUpdate = false;
        Faults.failNextId = false;
    }

    private JsonNode payAccount1() {
        ScreenClient client = new ScreenClient(mvc);
        client.signOn(jdbc.queryForObject("SELECT user_id FROM user_account WHERE account_id = 1", String.class));
        return client.post("COBIL00C", "ENTER", Map.of("accountId", "00000000001", "confirm", "Y"));
    }

    private void assertNothingChanged() {
        assertThat(jdbc.queryForObject("SELECT current_balance FROM account WHERE account_id = 1", BigDecimal.class))
            .isEqualByComparingTo("194.00");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM card_transaction", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT value FROM id_counter WHERE name = 'online_tran_id'", Long.class)).isZero();
    }

    @Test
    @Covers({"Q-COBIL00C-02", "ERR-COBIL00C-05"})
    void failed_insert_rolls_back_and_shows_the_step_message() {
        Faults.failInsert = true;
        JsonNode r = payAccount1();
        assertThat(r.at("/message").asText()).isEqualTo("Unable to Add Bill pay Transaction...");
        assertThat(r.at("/messageColor").asText()).isEqualTo("RED");
        assertThat(r.at("/fields").has("receipt")).isFalse();
        assertNothingChanged();
    }

    @Test
    @Covers({"Q-COBIL00C-02", "ERR-COBIL00C-06"})
    void failed_balance_update_also_removes_the_inserted_payment() {
        Faults.failUpdate = true;
        JsonNode r = payAccount1();
        assertThat(r.at("/message").asText()).isEqualTo("Unable to Update Account...");
        assertNothingChanged();
    }

    @Test
    @Covers({"Q-COBIL00C-02", "ERR-COBIL00C-03", "ERR-COBIL00C-04"})
    void failed_id_lookup_writes_nothing() {
        Faults.failNextId = true;
        JsonNode r = payAccount1();
        assertThat(r.at("/message").asText()).isEqualTo("Unable to lookup Transaction...");
        assertNothingChanged();
    }

    @Test
    @Covers({"Q-COBIL00C-01", "TS-COBIL00C-11"})
    void concurrent_payments_get_distinct_ids_and_all_commit() throws Exception {
        CurrentUser admin = new CurrentUser("ADMIN001", Role.ADMIN, "", "");
        List<Long> accounts = jdbc.queryForList(
            "SELECT TOP 12 account_id FROM account WHERE current_balance > 0 ORDER BY account_id", Long.class);
        ExecutorService pool = Executors.newFixedThreadPool(6);
        try {
            List<Callable<BillPaymentService.Outcome>> tasks = new ArrayList<>();
            for (long a : accounts) {
                tasks.add(() -> payments.pay(admin, a, false, null));
            }
            List<String> ids = new ArrayList<>();
            for (Future<BillPaymentService.Outcome> f : pool.invokeAll(tasks)) {
                assertThat(f.get()).isInstanceOf(BillPaymentService.Paid.class);
                ids.add(((BillPaymentService.Paid) f.get()).tranId());
            }
            assertThat(ids).doesNotHaveDuplicates().hasSize(accounts.size());
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM card_transaction", Integer.class)).isEqualTo(accounts.size());
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM account WHERE account_id IN ("
                + String.join(",", accounts.stream().map(String::valueOf).toList()) + ") AND current_balance <> 0", Integer.class))
                .isZero();
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @Covers({"Q-COBIL00C-01"})
    void concurrent_payments_of_the_same_account_pay_once() throws Exception {
        CurrentUser admin = new CurrentUser("ADMIN001", Role.ADMIN, "", "");
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Callable<BillPaymentService.Outcome>> tasks = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                tasks.add(() -> payments.pay(admin, 1L, false, null));
            }
            int paid = 0;
            for (Future<BillPaymentService.Outcome> f : pool.invokeAll(tasks)) {
                if (f.get() instanceof BillPaymentService.Paid) {
                    paid++;
                } else {
                    assertThat(f.get()).isInstanceOf(BillPaymentService.NothingToPay.class);
                }
            }
            assertThat(paid).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM card_transaction", Integer.class)).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }
}
