package com.carddemo.inquiry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.carddemo.account.api.AccountInquiry;
import com.carddemo.inquiry.internal.InquiryQueues;
import com.carddemo.test.Covers;
import com.carddemo.test.SampleData;
import com.carddemo.test.ScreenClient;
import com.carddemo.test.TestApplication;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * COACCT01 over RabbitMQ (real broker, private queues) and over REST, against the base sample data. Account
 * 77 fails to read (storage error, ERR-COACCT01-06) and account 66 throws an unexpected error (a poison
 * request) through the test's account store.
 */
@SpringBootTest(classes = {TestApplication.class, AccountInquiryMessagingTest.FailingStore.class})
@AutoConfigureMockMvc
class AccountInquiryMessagingTest {

    static final String SUFFIX = ".test-" + UUID.randomUUID();
    static final int RATE_LIMIT = 10;
    static final String ACCT1 = "ACCOUNT ID : 00000000001ACCOUNT STATUS : YBALANCE : 00000001940{CREDIT LIMIT : 00000020200{"
        + "CASH LIMIT : 00000010200{OPEN DATE : 2014-11-20EXPR DATE : 2025-05-20REIS DATE : 2025-05-20"
        + "CREDIT BAL : 00000000000{DEBIT BAL : 00000000000{GROUP ID : A000000000";
    static RabbitAdmin cleanup;
    static boolean loaded;

    @TestConfiguration(proxyBeanMethods = false)
    static class FailingStore {
        @Bean
        @Primary
        AccountInquiry failingAccountStore(@Qualifier("jdbcAccountInquiry") AccountInquiry real) {
            return id -> {
                if (id == 77) {
                    throw new DataAccessResourceFailureException("account store unavailable",
                        new SQLException("Cannot open database", "08004", 4060));
                }
                if (id == 66) {
                    throw new IllegalStateException("poison request");
                }
                return real.find(id);
            };
        }
    }

    @DynamicPropertySource
    static void privateQueues(DynamicPropertyRegistry r) {
        r.add("carddemo.mq.queue-suffix", () -> SUFFIX);
        r.add("spring.rabbitmq.listener.simple.auto-startup", () -> "true");
        r.add("carddemo.mq.rate-limit.requests", () -> Integer.toString(RATE_LIMIT));
        r.add("carddemo.mq.rate-limit.window", () -> "10m");
    }

    @Autowired
    RabbitTemplate rabbit;
    /** Closing it drops the listener's channels, as a broker restart or network failure would. */
    @Autowired
    CachingConnectionFactory connections;
    @Autowired
    RabbitAdmin admin;
    @Autowired
    InquiryQueues queues;
    @Autowired
    MockMvc mvc;
    @Autowired
    SampleData sample;
    @Autowired
    JdbcTemplate jdbc;

    /** Each test is its own requester, so the rate limit of one test does not reach another. */
    final String appId = "test-" + UUID.randomUUID();
    final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void data() {
        cleanup = admin;
        if (!loaded) {
            sample.load();
            loaded = true;
        }
    }

    @AfterAll
    static void deleteQueues() {
        if (cleanup != null) {
            for (String q : new String[] {InquiryQueues.ACCOUNT_REQUEST, InquiryQueues.ACCOUNT_REPLY, InquiryQueues.DATE_REQUEST,
                InquiryQueues.DATE_REPLY, InquiryQueues.ERROR, InquiryQueues.DEAD_LETTER}) {
                cleanup.deleteQueue(q + SUFFIX);
            }
        }
    }

    String send(byte[] body, String replyTo) {
        String id = "M-" + UUID.randomUUID();
        send(body, id, null, replyTo);
        return id;
    }

    /**
     * Publishes the requests in order on one channel: the broker keeps publish order only within a channel, and
     * the template's cached channels may otherwise alternate between sends.
     */
    List<String> sendInOrder(int count, byte[] body, String replyTo) {
        return rabbit.invoke(t -> {
            List<String> ids = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                ids.add(send(body, replyTo));
            }
            return ids;
        });
    }

    void send(byte[] body, String messageId, String correlationId, String replyTo) {
        MessageProperties p = new MessageProperties();
        p.setMessageId(messageId);
        p.setCorrelationId(correlationId);
        p.setReplyTo(replyTo);
        p.setAppId(appId);
        rabbit.send("", queues.accountRequest(), new Message(body, p));
    }

    static byte[] ascii(String s) {
        return s.getBytes(StandardCharsets.US_ASCII);
    }

    String tempQueue() {
        return admin.declareQueue(new Queue("test.reply." + UUID.randomUUID(), false, true, false));
    }

    Message reply(String queue) {
        Message m = rabbit.receive(queue, 15_000);
        assertThat(m).as("reply on " + queue).isNotNull();
        return m;
    }

    static String body(Message m) {
        return new String(m.getBody(), StandardCharsets.US_ASCII);
    }

    /** A reply of 1000 bytes whose text is {@code expected} followed by spaces. */
    static void assertReplyText(Message m, String expected) {
        String text = body(m);
        assertThat(text).hasSize(1000);
        assertThat(text.stripTrailing()).isEqualTo(expected);
    }

    /** The message on a shared queue (error queue, DLQ) that belongs to this request, skipping others. */
    Message onSharedQueue(String queue, java.util.function.Predicate<Message> mine) {
        long end = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < end) {
            Message m = rabbit.receive(queue, 1_000);
            if (m != null && mine.test(m)) {
                return m;
            }
        }
        throw new AssertionError("nothing for this request on " + queue);
    }

    Message errorRecordFor(String requestId) {
        return onSharedQueue(queues.error(), m -> requestId.equals(m.getMessageProperties().getCorrelationId()));
    }

    /** The error record laid out per MQ-ERR-DISPLAY plus the storage code field (Q-COACCT01-11). */
    static String record(String message, String cc, String rc, String queue, String storage) {
        String q = queue.length() > 48 ? queue.substring(0, 48) : queue;
        return (" ".repeat(25) + "  " + String.format("%-25s", message) + "  " + cc + "  " + rc + "  " + String.format("%-48s", q)
            + "  " + storage).stripTrailing();
    }

    JsonNode rest(ScreenClient client, String id, int status) throws Exception {
        return json.readTree(restBody(client, id, status));
    }

    /** The response body as sent, so decimal scale is checked on the wire rather than after parsing. */
    String restBody(ScreenClient client, String id, int status) throws Exception {
        MockHttpServletResponse res = mvc.perform(get("/api/inquiry/account/" + id).session(client.session())).andReturn().getResponse();
        assertThat(res.getStatus()).as("HTTP status for " + id).isEqualTo(status);
        return res.getContentAsString();
    }

    ScreenClient admin() {
        ScreenClient c = new ScreenClient(mvc);
        c.signOn("ADMIN001");
        return c;
    }

    @Test
    @Covers({"TS-COACCT01-01", "BR-COACCT01-04", "BR-COACCT01-05", "BR-COACCT01-06", "BR-COACCT01-07", "BR-COACCT01-10",
        "Q-COACCT01-01", "Q-COACCT01-02", "Q-COACCT01-03", "Q-COACCT01-05"})
    void account_request_gets_the_legacy_text_on_its_reply_to_queue_and_rest_returns_the_same_data_as_json() throws Exception {
        String replyTo = tempQueue();
        String id = "M1-" + UUID.randomUUID();
        send(ascii("INQA00000000001"), id, "C1", replyTo);
        Message m = reply(replyTo);
        assertReplyText(m, ACCT1);
        assertThat(body(m)).startsWith("ACCOUNT ID : 00000000001ACCOUNT STATUS : YBALANCE : 00000001940{CREDIT LIMIT : 00000020200{");
        assertThat(m.getMessageProperties().getCorrelationId()).isEqualTo(id);
        assertThat(m.getMessageProperties().getMessageId()).isNotBlank().isNotEqualTo(id);
        assertThat(m.getMessageProperties().getType()).isEqualTo("reply");
        assertThat(m.getMessageProperties().getContentType()).isEqualTo("text/plain");
        assertThat(rabbit.receive(replyTo, 1_000)).as("exactly one reply").isNull();

        assertThat(restBody(admin(), "00000000001", 200)).isEqualTo("{\"accountId\":\"00000000001\",\"activeStatus\":\"Y\",\"currentBalance\":194.00,"
            + "\"creditLimit\":2020.00,\"cashCreditLimit\":1020.00,\"openDate\":\"2014-11-20\",\"expirationDate\":\"2025-05-20\","
            + "\"reissueDate\":\"2025-05-20\",\"currentCycleCredit\":0.00,\"currentCycleDebit\":0.00,\"groupId\":\"A000000000\"}");
    }

    @Test
    @Covers({"Q-COACCT01-01", "Q-COACCT01-04", "Q-COACCT01-06", "C-KEYLEN"})
    void rest_takes_only_the_11_digit_account_id_and_answers_anything_else_as_the_queue_does() throws Exception {
        String replyTo = tempQueue();
        send(ascii("INQA7"), replyTo);
        String queueShort = body(reply(replyTo)).stripTrailing();
        assertThat(queueShort).isEqualTo("INVALID REQUEST PARAMETERS ACCT ID : 7          FUNCTION : INQA");
        ScreenClient admin = admin();
        assertThat(rest(admin, "7", 400).at("/message").asText()).isEqualTo(queueShort);
        assertThat(rest(admin, "0000000007", 400).at("/message").asText())
            .isEqualTo("INVALID REQUEST PARAMETERS ACCT ID : 0000000007 FUNCTION : INQA");
        assertThat(rest(admin, "000000000007", 400).at("/message").asText())
            .isEqualTo("INVALID REQUEST PARAMETERS ACCT ID : 00000000000FUNCTION : INQA");
        assertThat(rest(admin, "ABCDEFGHIJK", 400).at("/message").asText())
            .isEqualTo("INVALID REQUEST PARAMETERS ACCT ID : ABCDEFGHIJKFUNCTION : INQA");
        assertThat(rest(admin, "0000000000A", 400).at("/message").asText())
            .isEqualTo("INVALID REQUEST PARAMETERS ACCT ID : 0000000000AFUNCTION : INQA");
        assertThat(rest(admin, "00000000007", 200).at("/accountId").asText()).isEqualTo("00000000007");
    }

    @Test
    @Covers({"Q-COACCT01-05"})
    void negative_amounts_are_overpunched_on_the_queue_and_signed_decimals_on_rest() throws Exception {
        jdbc.update("UPDATE account SET current_balance = -56.77, curr_cycle_debit = 1234567890.12 WHERE account_id = 3");
        try {
            String replyTo = tempQueue();
            send(ascii("INQA00000000003"), replyTo);
            String text = body(reply(replyTo));
            assertThat(text.substring(52, 64)).isEqualTo("00000000567P");
            assertThat(text.substring(219, 231)).isEqualTo("12345678901B");
            JsonNode r = rest(admin(), "00000000003", 200);
            assertThat(r.at("/currentBalance").decimalValue()).isEqualByComparingTo("-56.77");
            assertThat(restBody(admin(), "00000000003", 200)).contains("\"currentBalance\":-56.77,").contains("\"currentCycleDebit\":1234567890.12,");
        } finally {
            loaded = false;
        }
    }

    @Test
    @Covers({"TS-COACCT01-02", "BR-COACCT01-08", "VR-COACCT01-02"})
    void unknown_account_gets_the_not_found_reply_and_rest_404() throws Exception {
        String replyTo = tempQueue();
        send(ascii("INQA00000099999"), replyTo);
        assertReplyText(reply(replyTo), "INVALID REQUEST PARAMETERS ACCT ID : 00000099999");
        assertThat(rest(admin(), "00000099999", 404).at("/message").asText())
            .isEqualTo("INVALID REQUEST PARAMETERS ACCT ID : 00000099999");
    }

    @Test
    @Covers({"TS-COACCT01-03", "TS-COACCT01-04", "BR-COACCT01-09", "VR-COACCT01-01"})
    void wrong_function_or_zero_key_gets_the_invalid_request_reply() throws Exception {
        String replyTo = tempQueue();
        send(ascii("INQX00000000001"), replyTo);
        assertReplyText(reply(replyTo), "INVALID REQUEST PARAMETERS ACCT ID : 00000000001FUNCTION : INQX");
        send(ascii("inqa00000000001"), replyTo);
        assertReplyText(reply(replyTo), "INVALID REQUEST PARAMETERS ACCT ID : 00000000001FUNCTION : inqa");
        send(ascii("INQA00000000000"), replyTo);
        assertReplyText(reply(replyTo), "INVALID REQUEST PARAMETERS ACCT ID : 00000000000FUNCTION : INQA");
        assertThat(rest(admin(), "00000000000", 400).at("/message").asText())
            .isEqualTo("INVALID REQUEST PARAMETERS ACCT ID : 00000000000FUNCTION : INQA");
        assertThat(rest(admin(), "ABC", 400).at("/message").asText()).isEqualTo("INVALID REQUEST PARAMETERS ACCT ID : ABC        FUNCTION : INQA");
        assertThat(rest(admin(), "123456789012", 400).at("/message").asText())
            .isEqualTo("INVALID REQUEST PARAMETERS ACCT ID : 12345678901FUNCTION : INQA");
    }

    @Test
    @Covers({"Q-COACCT01-04", "C-KEYLEN"})
    void rest_invalid_request_text_is_the_queue_reply_for_the_same_id() throws Exception {
        String replyTo = tempQueue();
        ScreenClient admin = admin();
        for (String id : List.of("7", "000000000007", "ABCDEFGHIJK", "ABC", "00000000000")) {
            send(ascii("INQA" + id), replyTo);
            String queue = body(reply(replyTo)).stripTrailing();
            assertThat(queue).as("queue reply for " + id).startsWith("INVALID REQUEST PARAMETERS ACCT ID : ").endsWith("FUNCTION : INQA");
            assertThat(rest(admin, id, 400).at("/message").asText()).as("REST message for " + id).isEqualTo(queue);
        }
    }

    @Test
    @Covers({"TS-COACCT01-05", "BR-COACCT01-11"})
    void queued_requests_are_answered_one_by_one_in_queue_order() {
        String replyTo = tempQueue();
        List<String> ids = rabbit.invoke(t -> {
            List<String> sent = new ArrayList<>();
            for (int i = 1; i <= 3; i++) {
                sent.add(send(ascii("INQA0000000000" + i), replyTo));
            }
            return sent;
        });
        for (int i = 1; i <= 3; i++) {
            Message m = reply(replyTo);
            assertThat(m.getMessageProperties().getCorrelationId()).isEqualTo(ids.get(i - 1));
            assertThat(body(m)).startsWith("ACCOUNT ID : 0000000000" + i);
        }
    }

    @Test
    @Covers({"TS-COACCT01-06", "TS-COACCT01-07", "BR-COACCT01-02", "Q-COACCT01-02", "Q-COACCT01-03"})
    void without_reply_to_the_fixed_reply_queue_is_used_and_correlation_is_the_request_message_id() {
        String id = "F-" + UUID.randomUUID();
        send(ascii("INQA00000000002"), id, null, null);
        Message m = onSharedQueue(queues.accountReply(), x -> id.equals(x.getMessageProperties().getCorrelationId()));
        assertThat(body(m)).startsWith("ACCOUNT ID : 00000000002");

        String replyTo = tempQueue();
        send(ascii("INQA00000000002"), null, "OWN-CORRELATION", replyTo);
        assertThat(reply(replyTo).getMessageProperties().getCorrelationId())
            .as("no message id: the requester's own correlation id is kept").isEqualTo("OWN-CORRELATION");
    }

    @Test
    @Covers({"TS-COACCT01-08", "ERR-COACCT01-06", "BR-COACCT01-14", "Q-COACCT01-07", "Q-COACCT01-11", "Q-COACCT01-16"})
    void read_error_sends_an_error_reply_and_an_error_record_and_consumes_the_request() throws Exception {
        String replyTo = tempQueue();
        String id = send(ascii("INQA00000000077"), replyTo);
        String expected = record("ERROR WHILE READING ACCTF", "00", "00000", queues.accountRequest(), "4060");
        Message err = reply(replyTo);
        assertThat(err.getMessageProperties().getType()).isEqualTo("error");
        assertThat(err.getMessageProperties().getCorrelationId()).isEqualTo(id);
        assertReplyText(err, expected);
        Message rec = errorRecordFor(id);
        assertReplyText(rec, expected);

        String next = send(ascii("INQA00000000001"), replyTo);
        Message ok = reply(replyTo);
        assertThat(ok.getMessageProperties().getCorrelationId()).isEqualTo(next);
        assertReplyText(ok, ACCT1);
        assertThat(rabbit.receive(queues.deadLetter(), 1_000)).as("the failed request was acknowledged, not dead-lettered").isNull();
        assertThat(rest(admin(), "00000000077", 503).at("/message").asText()).isEqualTo("ERROR WHILE READING ACCTF");

        // Q-COACCT01-16: a second error builds a new record; nothing of the first remains.
        String id2 = send(ascii("INQA00000000077"), null);
        assertReplyText(errorRecordFor(id2), expected);
    }

    @Test
    @Covers({"TS-COACCT01-09", "VR-COACCT01-03", "Q-COACCT01-08", "Q-COACCT01-04", "Q-COACCT01-06"})
    void oversized_short_and_unparsable_requests_get_the_invalid_reply_and_do_not_block_the_queue() {
        String replyTo = tempQueue();
        byte[] big = new byte[1200];
        Arrays.fill(big, (byte) 'X');
        System.arraycopy(ascii("INQA00000000001"), 0, big, 0, 15);
        send(big, replyTo);
        assertReplyText(reply(replyTo), "INVALID REQUEST PARAMETERS ACCT ID : 00000000001FUNCTION : INQA");
        byte[] exactly1000 = Arrays.copyOf(big, 1000);
        send(exactly1000, replyTo);
        assertReplyText(reply(replyTo), ACCT1);
        send(new byte[] {(byte) 0xC9, (byte) 0xD5, 0x00, 0x01, (byte) 0xFF, 'Q', 'A', '0', '0', '0', '0', '0', '0', '0', '1', '2'}, replyTo);
        assertReplyText(reply(replyTo), "INVALID REQUEST PARAMETERS ACCT ID : ?QA00000001FUNCTION : ????");
        send(ascii("INQA0000000000X"), replyTo);
        assertReplyText(reply(replyTo), "INVALID REQUEST PARAMETERS ACCT ID : 0000000000XFUNCTION : INQA");
        send(ascii("INQA 0000000001"), replyTo);
        assertReplyText(reply(replyTo), "INVALID REQUEST PARAMETERS ACCT ID :  0000000001FUNCTION : INQA");
        send(ascii("INQA00000000001"), replyTo);
        assertReplyText(reply(replyTo), ACCT1);
        assertThat(rabbit.receive(queues.deadLetter(), 1_000)).isNull();
    }

    @Test
    @Covers({"TS-COACCT01-14", "Q-COACCT01-04"})
    void request_shorter_than_15_bytes_uses_only_its_own_bytes() {
        String replyTo = tempQueue();
        send(ascii("INQA00000000002"), replyTo);
        assertThat(body(reply(replyTo))).startsWith("ACCOUNT ID : 00000000002");
        send(ascii("INQA1"), replyTo);
        assertReplyText(reply(replyTo), "INVALID REQUEST PARAMETERS ACCT ID : 1          FUNCTION : INQA");
        send(new byte[0], replyTo);
        assertReplyText(reply(replyTo), "INVALID REQUEST PARAMETERS ACCT ID :            FUNCTION :");
    }

    @Test
    @Covers({"TS-COACCT01-13", "ERR-COACCT01-07", "Q-COACCT01-07"})
    void full_reply_queue_is_reported_on_the_error_queue_and_the_request_is_consumed() {
        Map<String, Object> args = new HashMap<>();
        args.put("x-max-length", 1);
        args.put("x-overflow", "reject-publish");
        String full = admin.declareQueue(new Queue("test.full." + UUID.randomUUID(), false, true, false, args));
        rabbit.send("", full, new Message(ascii("occupied")));
        String id = send(ascii("INQA00000000001"), full);
        assertReplyText(errorRecordFor(id), record("MQPUT ERR", "02", "02053", full, ""));
        assertThat(body(rabbit.receive(full, 1_000))).isEqualTo("occupied");
        assertThat(rabbit.receive(full, 1_000)).as("no reply fits").isNull();
        String replyTo = tempQueue();
        send(ascii("INQA00000000001"), replyTo);
        assertReplyText(reply(replyTo), ACCT1);
        assertThat(rabbit.receive(queues.deadLetter(), 1_000)).isNull();
    }

    @Test
    @Covers({"TS-COACCT01-12", "ERR-COACCT01-04"})
    void reply_to_a_queue_that_does_not_exist_is_reported_on_the_error_queue() {
        String missing = "test.missing." + UUID.randomUUID();
        String id = send(ascii("INQA00000000001"), missing);
        assertReplyText(errorRecordFor(id), record("MQPUT ERR", "02", "02085", missing, ""));
    }

    @Test
    @Covers({"Q-COACCT01-09"})
    void a_closed_broker_connection_is_recovered_and_the_next_request_is_answered() {
        connections.resetConnection();
        String replyTo = tempQueue();
        String id = send(ascii("INQA00000000001"), replyTo);
        Message m = rabbit.receive(replyTo, 30_000);
        assertThat(m).as("reply after the connection was closed").isNotNull();
        assertThat(m.getMessageProperties().getCorrelationId()).isEqualTo(id);
        assertReplyText(m, ACCT1);
        assertThat(admin.getQueueInfo(queues.accountRequest()).getConsumerCount()).as("consumer back").isPositive();
    }

    @Test
    @Covers({"BR-COACCT01-01", "BR-COACCT01-03", "Q-COACCT01-17", "Q-COACCT01-10"})
    void inquiry_queues_are_declared_at_startup_with_the_dead_letter_and_bound_settings() {
        for (String q : List.of(queues.accountRequest(), queues.accountReply(), queues.error(), queues.deadLetter())) {
            assertThat(admin.getQueueInfo(q)).as(q).isNotNull();
        }
        assertThat(admin.getQueueInfo(queues.accountRequest()).getConsumerCount()).as("long-lived consumer").isPositive();
    }

    @Test
    @Covers({"TS-COACCT01-15", "NAV-COACCT01-01", "BR-COACCT01-13", "Q-COACCT01-17"})
    void the_listener_stays_up_after_an_idle_period() throws InterruptedException {
        String replyTo = tempQueue();
        Thread.sleep(6_000);
        send(ascii("INQA00000000001"), replyTo);
        assertReplyText(reply(replyTo), ACCT1);
    }

    @Test
    @Covers({"Q-COACCT01-08", "ERR-COACCT01-10"})
    void a_request_that_fails_three_deliveries_is_dead_lettered_and_the_next_request_is_answered() {
        String replyTo = tempQueue();
        String poison = send(ascii("INQA00000000066"), replyTo);
        String next = send(ascii("INQA00000000001"), replyTo);
        Message dead = onSharedQueue(queues.deadLetter(), m -> poison.equals(m.getMessageProperties().getMessageId()));
        assertThat(body(dead)).isEqualTo("INQA00000000066");
        Message ok = reply(replyTo);
        assertThat(ok.getMessageProperties().getCorrelationId()).isEqualTo(next);
        assertReplyText(ok, ACCT1);
        assertThat(rabbit.receive(replyTo, 1_000)).as("the poison request got no reply").isNull();
    }

    @Test
    @Covers({"TS-COACCT01-11", "ERR-COACCT01-01", "ERR-COACCT01-08"})
    void an_error_record_the_error_queue_cannot_take_sends_the_request_to_the_dead_letter_queue() {
        admin.deleteQueue(queues.error());
        try {
            String poison = send(ascii("INQA00000000077"), null);
            Message dead = onSharedQueue(queues.deadLetter(), m -> poison.equals(m.getMessageProperties().getMessageId()));
            assertThat(body(dead)).isEqualTo("INQA00000000077");
        } finally {
            admin.declareQueue(new Queue(queues.error(), true));
        }
        String replyTo = tempQueue();
        send(ascii("INQA00000000001"), replyTo);
        assertReplyText(reply(replyTo), ACCT1);
    }

    @Test
    @Covers({"Q-COACCT01-15", "C-ROLE", "C-SCOPE", "D-USER-ACCT"})
    void rest_needs_a_session_and_answers_other_users_accounts_as_not_found() throws Exception {
        assertThat(mvc.perform(get("/api/inquiry/account/1")).andReturn().getResponse().getStatus()).isEqualTo(401);
        String owner = jdbc.queryForObject("SELECT user_id FROM user_account WHERE account_id = 1", String.class);
        long other = jdbc.queryForObject("SELECT MIN(account_id) FROM user_account WHERE user_id <> ?", Long.class, owner);
        ScreenClient user = new ScreenClient(mvc);
        user.signOn(owner);
        assertThat(rest(user, "00000000001", 200).at("/currentBalance").decimalValue()).isEqualByComparingTo("194.00");
        String otherKey = String.format("%011d", other);
        assertThat(rest(user, otherKey, 404).at("/message").asText()).isEqualTo("INVALID REQUEST PARAMETERS ACCT ID : " + otherKey);
        assertThat(rest(admin(), otherKey, 200).at("/accountId").asText()).isEqualTo(otherKey);
    }

    @Test
    @Covers({"Q-CODATE01-14", "Q-COACCT01-15"})
    void a_requester_over_its_rate_limit_gets_an_error_reply() {
        String replyTo = tempQueue();
        List<String> ids = sendInOrder(RATE_LIMIT + 1, ascii("INQA00000000001"), replyTo);
        List<String> types = new ArrayList<>();
        for (int i = 0; i < RATE_LIMIT + 1; i++) {
            Message m = reply(replyTo);
            types.add(m.getMessageProperties().getType());
            if ("error".equals(m.getMessageProperties().getType())) {
                assertReplyText(m, record("REQUEST RATE EXCEEDED", "02", "00000", queues.accountRequest(), ""));
                assertThat(m.getMessageProperties().getCorrelationId()).isEqualTo(ids.get(RATE_LIMIT));
            }
        }
        List<String> expected = new ArrayList<>(java.util.Collections.nCopies(RATE_LIMIT, "reply"));
        expected.add("error");
        assertThat(types).isEqualTo(expected);
        assertThat(errorRecordFor(ids.get(RATE_LIMIT))).isNotNull();
    }
}
