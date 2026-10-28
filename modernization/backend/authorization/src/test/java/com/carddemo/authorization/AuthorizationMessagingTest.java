package com.carddemo.authorization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.carddemo.authorization.internal.AuthReply;
import com.carddemo.authorization.messaging.AuthQueues;
import com.carddemo.migration.PendingAuthSegments;
import com.carddemo.test.Covers;
import com.carddemo.test.SampleData;
import com.carddemo.test.ScreenClient;
import com.carddemo.test.TestApplication;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * mq-roundtrip for COPAUA0C: every spec scenario over RabbitMQ (real broker, private queues, a fresh message id
 * and a temporary reply queue per request) and, where the scenario is not about the queue itself, over
 * {@code POST /api/authorizations} with the same expected result. Scenarios changed by a ledger FIX assert the
 * fixed behavior.
 */
@SpringBootTest(classes = {TestApplication.class, AuthorizationMessagingTest.Clocks.class})
@AutoConfigureMockMvc
class AuthorizationMessagingTest {

    static final String SUFFIX = ".test-" + UUID.randomUUID();
    static RabbitAdmin cleanup;

    @TestConfiguration(proxyBeanMethods = false)
    static class Clocks {
        @Bean
        @Primary
        AuthFixtures.MutableClock mutableClock() {
            return new AuthFixtures.MutableClock();
        }
    }

    @DynamicPropertySource
    static void privateQueues(DynamicPropertyRegistry r) {
        r.add("carddemo.mq.queue-suffix", () -> SUFFIX);
        r.add("spring.rabbitmq.listener.simple.auto-startup", () -> "true");
    }

    enum Transport { QUEUE, REST }

    @Autowired
    RabbitTemplate rabbit;
    @Autowired
    RabbitAdmin admin;
    @Autowired
    AuthQueues queues;
    @Autowired
    MockMvc mvc;
    @Autowired
    SampleData sample;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    Clock clock;

    final ObjectMapper json = new ObjectMapper();
    ScreenClient adminClient;

    @AfterAll
    static void deleteQueues() {
        if (cleanup != null) {
            for (String q : new String[] {AuthQueues.REQUEST, AuthQueues.REPLY, AuthQueues.DEAD_LETTER}) {
                cleanup.deleteQueue(q + SUFFIX);
            }
        }
    }

    void reset() {
        cleanup = admin;
        AuthFixtures.load(sample, jdbc);
        ((AuthFixtures.MutableClock) clock).set(LocalDateTime.of(2024, 3, 5, 10, 0));
        adminClient = null;
    }

    String tempQueue() {
        return admin.declareQueue(new Queue("test.reply." + UUID.randomUUID(), false, true, false));
    }

    Message publish(byte[] body, String messageId, String replyTo) {
        MessageProperties p = new MessageProperties();
        p.setMessageId(messageId);
        p.setReplyTo(replyTo);
        Message m = new Message(body, p);
        rabbit.send("", queues.request(), m);
        return m;
    }

    Message receive(String queue) {
        Message m = rabbit.receive(queue, 15_000);
        assertThat(m).as("message on " + queue).isNotNull();
        return m;
    }

    /** One request over the transport; returns the reply text (REST: rebuilt from the JSON fields). */
    String authorize(Transport t, String request) throws Exception {
        if (t == Transport.QUEUE) {
            String replyTo = tempQueue();
            String id = "A-" + UUID.randomUUID();
            publish(request.getBytes(StandardCharsets.US_ASCII), id, replyTo);
            Message m = receive(replyTo);
            assertThat(m.getMessageProperties().getCorrelationId()).isEqualTo(id);
            assertThat(m.getMessageProperties().getType()).isEqualTo("reply");
            assertThat(rabbit.receive(replyTo, 300)).as("exactly one reply").isNull();
            return new String(m.getBody(), StandardCharsets.US_ASCII);
        }
        JsonNode r = json.readTree(rest(request).getContentAsString());
        return new AuthReply(r.get("cardNum").asText(), r.get("transactionId").asText(), r.get("authIdCode").asText(),
            r.get("respCode").asText(), r.get("reasonCode").asText(), new BigDecimal(r.get("approvedAmount").asText())).text();
    }

    MockHttpServletResponse rest(String request) throws Exception {
        if (adminClient == null) {
            adminClient = new ScreenClient(mvc);
            adminClient.signOn("ADMIN001");
        }
        String[] f = request.split(",", -1);
        String[] names = {"authDate", "authTime", "cardNum", "authType", "cardExpiryDate", "messageType", "messageSource",
            "processingCode", "transactionAmt", "merchantCategoryCode", "acqrCountryCode", "posEntryMode", "merchantId",
            "merchantName", "merchantCity", "merchantState", "merchantZip", "transactionId"};
        Map<String, Object> body = new LinkedHashMap<>();
        for (int i = 0; i < names.length; i++) {
            body.put(names[i], f[i]);
        }
        MockHttpServletResponse res = mvc.perform(post("/api/authorizations").session(adminClient.session()).with(csrf())
            .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body))).andReturn().getResponse();
        assertThat(res.getStatus()).as(res.getContentAsString()).isEqualTo(200);
        return res;
    }

    Map<String, Object> summary(long acct) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM pending_auth_summary WHERE account_id = ?", acct);
        return rows.isEmpty() ? null : rows.get(0);
    }

    Map<String, Object> newestDetail(long acct) {
        return jdbc.queryForMap("SELECT TOP 1 * FROM pending_auth_detail WHERE account_id = ? ORDER BY auth_ts DESC, auth_id DESC", acct);
    }

    int count(String sql) {
        return jdbc.queryForObject(sql, Integer.class);
    }

    List<Map<String, Object>> errorLog() {
        return jdbc.queryForList("SELECT * FROM auth_error_log ORDER BY error_id");
    }

    static List<Object> money(Map<String, Object> row, String... columns) {
        List<Object> out = new ArrayList<>();
        for (String c : columns) {
            out.add(row.get(c) instanceof BigDecimal d ? d.stripTrailingZeros() : row.get(c));
        }
        return out;
    }

    static void assertMoney(Object actual, String expected) {
        assertThat((BigDecimal) actual).isEqualByComparingTo(expected);
    }

    @Test
    @Covers({"TS-COPAUA0C-01", "BR-COPAUA0C-03", "BR-COPAUA0C-04", "BR-COPAUA0C-05", "BR-COPAUA0C-06", "BR-COPAUA0C-09",
        "BR-COPAUA0C-11", "BR-COPAUA0C-12", "BR-COPAUA0C-14", "BR-COPAUA0C-16", "BR-COPAUA0C-17", "BR-COPAUA0C-18",
        "Q-COPAUA0C-01", "Q-COPAUA0C-06", "Q-COPAUA0C-18", "Q-COPAUA0C-14", "Q-COPAUA0C-17", "Q-COPAUA0C-05"})
    void example_request_is_approved_and_recorded() throws Exception {
        for (Transport t : Transport.values()) {
            reset();
            String reply = authorize(t, AuthFixtures.EXAMPLE);
            assertThat(reply).as(t.name()).isEqualTo("4859452612877065,TXN000000000001,143015,00,0000,        100.00,").hasSize(63);
            Map<String, Object> s = summary(7);
            assertThat(s.get("approved_count")).isEqualTo(51);
            assertMoney(s.get("approved_amt"), "174.79");
            assertMoney(s.get("credit_balance"), "174.79");
            assertMoney(s.get("cash_balance"), "0.00");
            assertMoney(s.get("credit_limit"), "2065.00");
            assertMoney(s.get("cash_limit"), "264.00");
            assertThat(s.get("declined_count")).isEqualTo(0);
            Map<String, Object> d = newestDetail(7);
            assertThat(d).containsEntry("auth_orig_date", "231026").containsEntry("auth_orig_time", "143015")
                .containsEntry("auth_resp_code", "00").containsEntry("auth_resp_reason", "0000").containsEntry("match_status", "P")
                .containsEntry("auth_fraud", "").containsEntry("auth_id_code", "143015").containsEntry("card_num", AuthFixtures.CARD_7)
                .containsEntry("auth_type", "0100").containsEntry("card_expiry_date", "1226").containsEntry("message_type", "1234")
                .containsEntry("message_source", "102030").containsEntry("processing_code", "000000")
                .containsEntry("merchant_category_code", "5442").containsEntry("acqr_country_code", "USA")
                .containsEntry("pos_entry_mode", "01").containsEntry("merchant_id", "MERCH0000000001")
                .containsEntry("merchant_name", "Amazon.com").containsEntry("merchant_city", "Wilmington")
                .containsEntry("merchant_state", "DE").containsEntry("merchant_zip", "19801")
                .containsEntry("transaction_id", "TXN000000000001");
            assertThat(d.get("fraud_rpt_date")).isNull();
            assertMoney(d.get("approved_amt"), "100.00");
            assertMoney(d.get("transaction_amt"), "100.00");
            assertThat(count("SELECT COUNT(*) FROM pending_auth_detail WHERE account_id = 7")).isEqualTo(51);
            // No base file is updated (Data effects): the account balance stays.
            assertMoney(jdbc.queryForObject("SELECT current_balance FROM account WHERE account_id = 7", BigDecimal.class), "193.00");
        }
    }

    @Test
    @Covers({"TS-COPAUA0C-02", "BR-COPAUA0C-06", "BR-COPAUA0C-09", "BR-COPAUA0C-10", "BR-COPAUA0C-14", "Q-COPAUA0C-04",
        "Q-COPAUA0C-10"})
    void summary_available_credit_boundary() throws Exception {
        for (Transport t : Transport.values()) {
            reset();
            assertThat(authorize(t, AuthFixtures.request(AuthFixtures.CARD_7, "1990.22")))
                .isEqualTo("4859452612877065,TXN000000000001,143015,05,4100,          0.00,");
            Map<String, Object> s = summary(7);
            assertThat(s.get("declined_count")).isEqualTo(1);
            assertMoney(s.get("declined_amt"), "1990.22");
            assertMoney(s.get("credit_balance"), "74.79");
            assertThat(newestDetail(7)).containsEntry("match_status", "D").containsEntry("auth_resp_code", "05");
            assertMoney(newestDetail(7).get("approved_amt"), "0.00");
            assertMoney(newestDetail(7).get("transaction_amt"), "1990.22");
            assertThat(authorize(t, AuthFixtures.request(AuthFixtures.CARD_7, "1990.21"))).contains(",00,0000,       1990.21,");
            assertMoney(summary(7).get("credit_balance"), "2065.00");
        }
    }

    @Test
    @Covers({"TS-COPAUA0C-03", "BR-COPAUA0C-07", "BR-COPAUA0C-14"})
    void account_available_credit_when_no_summary_exists() throws Exception {
        for (Transport t : Transport.values()) {
            reset();
            assertThat(summary(2)).isNull();
            assertThat(authorize(t, AuthFixtures.request(AuthFixtures.CARD_2, "5972.00"))).contains(",00,0000,       5972.00,");
            Map<String, Object> s = summary(2);
            assertThat(s.get("customer_id")).isEqualTo(2);
            assertMoney(s.get("credit_limit"), "6130.00");
            assertMoney(s.get("cash_limit"), "5448.00");
            assertThat(s.get("approved_count")).isEqualTo(1);
            assertMoney(s.get("approved_amt"), "5972.00");
            assertMoney(s.get("credit_balance"), "5972.00");

            reset();
            assertThat(authorize(t, AuthFixtures.request(AuthFixtures.CARD_2, "5972.01"))).contains(",05,4100,          0.00,");
            s = summary(2);
            assertThat(s.get("declined_count")).isEqualTo(1);
            assertThat(s.get("approved_count")).isEqualTo(0);
            assertMoney(s.get("declined_amt"), "5972.01");
        }
    }

    @Test
    @Covers({"TS-COPAUA0C-04", "BR-COPAUA0C-08", "BR-COPAUA0C-10", "BR-COPAUA0C-13", "BR-COPAUA0C-19", "ERR-COPAUA0C-04",
        "Q-COPAUA0C-13", "Q-COPAUA0C-02"})
    void unknown_card_is_declined_3100_with_a_masked_warning_and_no_record() throws Exception {
        for (Transport t : Transport.values()) {
            reset();
            int details = count("SELECT COUNT(*) FROM pending_auth_detail");
            int summaries = count("SELECT COUNT(*) FROM pending_auth_summary");
            assertThat(authorize(t, AuthFixtures.request("9999999999999999", "10.00")))
                .isEqualTo("9999999999999999,TXN000000000001,143015,05,3100,          0.00,");
            List<Map<String, Object>> log = errorLog();
            assertThat(log).hasSize(1);
            assertThat(log.get(0)).containsEntry("location", "A001").containsEntry("err_level", "W").containsEntry("subsystem", "A")
                .containsEntry("message", "CARD NOT FOUND IN XREF").containsEntry("event_key", "************9999")
                .containsEntry("application", "CP00").containsEntry("program", "COPAUA0C")
                .containsEntry("err_date", "240305").containsEntry("err_time", "100000");
            assertThat(count("SELECT COUNT(*) FROM pending_auth_detail")).isEqualTo(details);
            assertThat(count("SELECT COUNT(*) FROM pending_auth_summary")).isEqualTo(summaries);
        }
    }

    @Test
    @Covers({"ERR-COPAUA0C-12"})
    void a_failure_to_store_the_error_record_is_ignored() throws Exception {
        for (Transport t : Transport.values()) {
            reset();
            jdbc.execute("EXEC sp_rename 'auth_error_log', 'auth_error_log_off'");
            String reply;
            try {
                reply = authorize(t, AuthFixtures.request("9999999999999999", "10.00"));
            } finally {
                jdbc.execute("EXEC sp_rename 'auth_error_log_off', 'auth_error_log'");
            }
            assertThat(reply).as(t.name()).isEqualTo("9999999999999999,TXN000000000001,143015,05,3100,          0.00,");
            assertThat(errorLog()).isEmpty();
        }
    }

    @Test
    @Covers({"Q-ENT-26"})
    void limits_and_amounts_of_a_billion_or_more_are_kept_exactly() throws Exception {
        for (Transport t : Transport.values()) {
            reset();
            jdbc.update("DELETE FROM pending_auth_detail WHERE account_id = 7");
            jdbc.update("DELETE FROM pending_auth_summary WHERE account_id = 7");
            jdbc.update("UPDATE account SET credit_limit = 2000000000.00, cash_credit_limit = 1500000000.50 WHERE account_id = 7");
            assertThat(authorize(t, AuthFixtures.request(AuthFixtures.CARD_7, "1999999000.00"))).as(t.name())
                .isEqualTo("4859452612877065,TXN000000000001,143015,00,0000, 1999999000.00,");
            Map<String, Object> s = summary(7);
            assertMoney(s.get("credit_limit"), "2000000000.00");
            assertMoney(s.get("cash_limit"), "1500000000.50");
            assertMoney(s.get("approved_amt"), "1999999000.00");
            assertMoney(s.get("credit_balance"), "1999999000.00");
            assertMoney(newestDetail(7).get("transaction_amt"), "1999999000.00");
        }
    }

    @Test
    @Covers({"TS-COPAUA0C-05", "Q-COPAUA0C-02", "BR-COPAUA0C-04"})
    void unknown_card_after_an_approval_is_still_declined() throws Exception {
        for (Transport t : Transport.values()) {
            reset();
            assertThat(authorize(t, AuthFixtures.EXAMPLE)).contains(",00,0000,");
            assertThat(authorize(t, AuthFixtures.request("9999999999999999", "10.00"))).contains(",05,3100,          0.00,");
        }
    }

    @Test
    @Covers({"ERR-COPAUA0C-04", "Q-COPAUA0C-02"})
    void unknown_card_right_after_a_known_card_on_the_same_listener_is_declined_without_the_previous_summary() throws Exception {
        reset();
        // The previous request found account 7's summary; the program would reuse it for the next card.
        assertThat(authorize(Transport.QUEUE, AuthFixtures.request(AuthFixtures.CARD_7, "5.00")))
            .isEqualTo("4859452612877065,TXN000000000001,143015,00,0000,          5.00,");
        String[] money = {"approved_count", "declined_count", "approved_amt", "declined_amt", "credit_balance", "cash_balance"};
        List<Object> before = money(summary(7), money);
        int details = count("SELECT COUNT(*) FROM pending_auth_detail");
        jdbc.update("DELETE FROM auth_error_log");
        assertThat(authorize(Transport.QUEUE, AuthFixtures.request("9999999999999999", "5.00")))
            .isEqualTo("9999999999999999,TXN000000000001,143015,05,3100,          0.00,");
        assertThat(errorLog()).singleElement().satisfies(e -> assertThat(e).containsEntry("location", "A001")
            .containsEntry("message", "CARD NOT FOUND IN XREF").containsEntry("event_key", "************9999"));
        assertThat(money(summary(7), money)).isEqualTo(before);
        assertThat(count("SELECT COUNT(*) FROM pending_auth_detail")).isEqualTo(details);
        // A declined known card (insufficient funds) before it changes nothing either.
        assertThat(authorize(Transport.QUEUE, AuthFixtures.request(AuthFixtures.CARD_7, "999999.00"))).contains(",05,4100,");
        assertThat(authorize(Transport.QUEUE, AuthFixtures.request("9999999999999999", "1.00")))
            .isEqualTo("9999999999999999,TXN000000000001,143015,05,3100,          0.00,");
    }

    @Test
    @Covers({"TS-COPAUA0C-06", "Q-COPAUA0C-01"})
    void two_messages_each_get_only_their_own_63_character_reply() throws Exception {
        reset();
        String replyTo = tempQueue();
        String first = "F-" + UUID.randomUUID();
        String second = "S-" + UUID.randomUUID();
        publish(AuthFixtures.EXAMPLE.getBytes(StandardCharsets.US_ASCII), first, replyTo);
        publish(AuthFixtures.request(AuthFixtures.CARD_2, "12.34").replace("TXN000000000001", "TXN000000000002")
            .getBytes(StandardCharsets.US_ASCII), second, replyTo);
        Message a = receive(replyTo);
        Message b = receive(replyTo);
        Map<String, String> byId = Map.of(a.getMessageProperties().getCorrelationId(), new String(a.getBody(), StandardCharsets.US_ASCII),
            b.getMessageProperties().getCorrelationId(), new String(b.getBody(), StandardCharsets.US_ASCII));
        assertThat(byId.get(first)).isEqualTo("4859452612877065,TXN000000000001,143015,00,0000,        100.00,");
        assertThat(byId.get(second)).isEqualTo("0923877193247330,TXN000000000002,143015,00,0000,         12.34,");
    }

    @Test
    @Covers({"TS-COPAUA0C-07", "BR-COPAUA0C-15", "Q-COPAUA0C-11", "Q-ENT-27"})
    void detail_key_comes_from_the_insert_timestamp_and_sorts_newest_first() throws Exception {
        for (Transport t : Transport.values()) {
            reset();
            ((AuthFixtures.MutableClock) clock).set(LocalDateTime.of(2023, 10, 26, 14, 30, 15, 123_000_000));
            authorize(t, AuthFixtures.request(AuthFixtures.CARD_2, "1.00"));
            authorize(t, AuthFixtures.request(AuthFixtures.CARD_2, "2.00"));
            List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT auth_id, auth_ts, transaction_amt FROM pending_auth_detail WHERE account_id = 2 ORDER BY auth_ts DESC, auth_id DESC");
            assertThat(rows).hasSize(2);
            LocalDateTime ts = ((java.sql.Timestamp) rows.get(0).get("auth_ts")).toLocalDateTime();
            assertThat(ts).isEqualTo(LocalDateTime.of(2023, 10, 26, 14, 30, 15, 123_000_000));
            assertThat(HexFormat.of().formatHex(PendingAuthSegments.detailKey(ts))).isEqualTo("76700c856984876c");
            // Same millisecond: both keys stay unique; the later insert is first.
            assertMoney(rows.get(0).get("transaction_amt"), "2.00");
        }
    }

    @Test
    @Covers({"TS-COPAUA0C-08", "BR-COPAUA0C-02", "Q-COPAUA0C-15", "NAV-COPAUA0C-01", "BR-COPAUA0C-01"})
    void long_lived_consumer_answers_more_than_501_messages() throws Exception {
        reset();
        String replyTo = tempQueue();
        int n = 520;
        for (int i = 0; i < n; i++) {
            publish(AuthFixtures.request("9999999999999999", "1.00").getBytes(StandardCharsets.US_ASCII), "L-" + i, replyTo);
        }
        int got = 0;
        while (got < n && rabbit.receive(replyTo, 15_000) != null) {
            got++;
        }
        assertThat(got).isEqualTo(n);
        assertThat(admin.getQueueInfo(queues.request()).getMessageCount()).isZero();
    }

    @Test
    @Covers({"TS-COPAUA0C-09", "ERR-COPAUA0C-08", "BR-COPAUA0C-13", "Q-COPAUA0C-06"})
    void reply_failure_is_logged_after_the_commit_and_the_request_is_consumed() throws Exception {
        reset();
        publish(AuthFixtures.EXAMPLE.getBytes(StandardCharsets.US_ASCII), "R-" + UUID.randomUUID(), "no.such.queue." + UUID.randomUUID());
        long deadline = System.currentTimeMillis() + 15_000;
        while (errorLog().isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(100);
        }
        List<Map<String, Object>> log = errorLog();
        assertThat(log).hasSize(1);
        assertThat(log.get(0)).containsEntry("location", "M004").containsEntry("err_level", "C").containsEntry("subsystem", "M")
            .containsEntry("message", "FAILED TO PUT ON REPLY MQ").containsEntry("event_key", "************7065");
        // Q-COPAUA0C-06 (FIX): the database update committed before the reply was attempted.
        assertThat(summary(7).get("approved_count")).isEqualTo(51);
        assertThat(rabbit.receive(queues.deadLetter(), 1_000)).as("request not dead-lettered").isNull();
        assertThat(admin.getQueueInfo(queues.request()).getMessageCount()).isZero();
    }

    @Test
    @Covers({"TS-COPAUA0C-11", "Q-COPAUA0C-09", "ERR-COPAUA0C-06"})
    void missing_customer_is_logged_but_does_not_decline() throws Exception {
        for (Transport t : Transport.values()) {
            reset();
            jdbc.update("DELETE FROM customer WHERE customer_id = 7");
            assertThat(authorize(t, AuthFixtures.request(AuthFixtures.CARD_7, "10.00"))).contains(",00,0000,         10.00,");
            assertThat(errorLog()).singleElement().satisfies(e -> assertThat(e).containsEntry("location", "A003")
                .containsEntry("err_level", "W").containsEntry("message", "CUST NOT FOUND IN XREF")
                .containsEntry("event_key", "000000007"));
            // Declined for funds with the customer missing: 3100 is evaluated first (Q-COPAUA0C-09).
            assertThat(authorize(t, AuthFixtures.request(AuthFixtures.CARD_7, "5000.00"))).contains(",05,3100,");
        }
    }

    @Test
    @Covers({"ERR-COPAUA0C-05", "Q-COPAUA0C-03"})
    void missing_account_is_declined_3100_without_copying_limits() throws Exception {
        for (Transport t : Transport.values()) {
            reset();
            jdbc.update("DELETE FROM account WHERE account_id = 7");
            assertThat(authorize(t, AuthFixtures.request(AuthFixtures.CARD_7, "10.00"))).contains(",05,3100,          0.00,");
            assertThat(errorLog()).singleElement().satisfies(e -> assertThat(e).containsEntry("location", "A002")
                .containsEntry("message", "ACCT NOT FOUND IN XREF").containsEntry("event_key", "00000000007"));
            Map<String, Object> s = summary(7);
            assertMoney(s.get("credit_limit"), "2065.00");
            assertThat(s.get("declined_count")).isEqualTo(1);
            assertMoney(s.get("declined_amt"), "10.00");
        }
    }

    @Test
    @Covers({"TS-COPAUA0C-12", "Q-COPAUA0C-12", "Q-COPAUA0C-08"})
    void negative_zero_and_non_numeric_amounts_are_declined_9000_without_records() throws Exception {
        for (Transport t : Transport.values()) {
            reset();
            int details = count("SELECT COUNT(*) FROM pending_auth_detail");
            Map<String, Object> before = summary(7);
            for (String amount : List.of("-50.00", "0.00", "ABC", "")) {
                assertThat(authorize(t, AuthFixtures.request(AuthFixtures.CARD_7, amount))).as(amount)
                    .isEqualTo("4859452612877065,TXN000000000001,143015,05,9000,          0.00,");
            }
            assertThat(count("SELECT COUNT(*) FROM pending_auth_detail")).isEqualTo(details);
            // The row version moves on any UPDATE, so an equal version means the summary was not rewritten.
            assertThat((byte[]) summary(7).get("row_version")).isEqualTo((byte[]) before.get("row_version"));
            // An unknown card with a bad amount keeps the not-found reason, which is evaluated first.
            assertThat(authorize(t, AuthFixtures.request("9999999999999999", "-1"))).contains(",05,3100,");
        }
    }

    @Test
    @Covers({"BR-COPAUA0C-12"})
    void request_without_reply_to_is_answered_on_the_fixed_reply_queue() {
        reset();
        String id = "F-" + UUID.randomUUID();
        publish(AuthFixtures.EXAMPLE.getBytes(StandardCharsets.US_ASCII), id, null);
        Message m = receive(queues.reply());
        assertThat(m.getMessageProperties().getCorrelationId()).isEqualTo(id);
        assertThat(new String(m.getBody(), StandardCharsets.US_ASCII)).startsWith("4859452612877065,TXN000000000001,143015,00,");
    }

    @Test
    @Covers({"ERR-COPAUA0C-02", "Q-COPAUA0C-16"})
    void oversized_and_unparsable_requests_are_dead_lettered_and_the_next_request_is_answered() {
        reset();
        String replyTo = tempQueue();
        List<String> ids = new ArrayList<>();
        byte[] oversized = (AuthFixtures.EXAMPLE + " ".repeat(400)).getBytes(StandardCharsets.US_ASCII);
        byte[][] bad = {oversized, "231026,143015,4859452612877065".getBytes(StandardCharsets.US_ASCII),
            {(byte) 0xC1, (byte) 0xFF, 0x00, 0x01}};
        for (byte[] b : bad) {
            String id = "P-" + UUID.randomUUID();
            ids.add(id);
            publish(b, id, replyTo);
        }
        String good = "G-" + UUID.randomUUID();
        publish(AuthFixtures.EXAMPLE.getBytes(StandardCharsets.US_ASCII), good, replyTo);
        Message reply = receive(replyTo);
        assertThat(reply.getMessageProperties().getCorrelationId()).isEqualTo(good);
        List<String> dead = new ArrayList<>();
        for (int i = 0; i < bad.length; i++) {
            dead.add(receive(queues.deadLetter()).getMessageProperties().getMessageId());
        }
        assertThat(dead).containsExactlyInAnyOrderElementsOf(ids);
        assertThat(errorLog()).hasSize(3).allSatisfy(e -> assertThat(e).containsEntry("location", "M003")
            .containsEntry("subsystem", "M").containsEntry("err_level", "C").containsEntry("message", "FAILED TO READ REQUEST MQ"));
    }

    @Test
    @Covers({"C-ROLE"})
    void rest_endpoint_needs_an_admin_session() throws Exception {
        reset();
        assertThat(mvc.perform(post("/api/authorizations").with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andReturn().getResponse().getStatus()).isEqualTo(401);
        ScreenClient user = new ScreenClient(mvc);
        user.signOn("USER0001");
        assertThat(mvc.perform(post("/api/authorizations").session(user.session()).with(csrf())
            .contentType(MediaType.APPLICATION_JSON).content("{}")).andReturn().getResponse().getStatus()).isEqualTo(403);
    }
}
