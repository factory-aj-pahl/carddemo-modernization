package com.carddemo.inquiry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.carddemo.inquiry.internal.InquiryQueues;
import com.carddemo.test.Covers;
import com.carddemo.test.SampleData;
import com.carddemo.test.ScreenClient;
import com.carddemo.test.TestApplication;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/** CODATE01 over RabbitMQ (real broker, private queues) and over REST, at the spec's clock 2024-03-21 14:05:09. */
@SpringBootTest(classes = {TestApplication.class, DateInquiryMessagingTest.TestClockConfig.class})
@AutoConfigureMockMvc
class DateInquiryMessagingTest {

    static final String SUFFIX = ".test-" + UUID.randomUUID();
    static final String EXPECTED = "SYSTEM DATE : 03-21-2024SYSTEM TIME : 14:05:09";
    static final ZonedDateTime START = ZonedDateTime.of(2024, 3, 21, 14, 5, 9, 0, ZoneId.of("America/Chicago"));
    static final int RATE_LIMIT = 10;
    static final TestClock CLOCK = new TestClock();
    static RabbitAdmin cleanup;

    /**
     * The spec's region clock. A test can make it advance one second per reading (two queued requests processed
     * one after the other) or fail (an unexpected error while processing a request).
     */
    static final class TestClock extends Clock {
        volatile Instant now = START.toInstant();
        volatile boolean stepping;
        volatile boolean failing;

        @Override
        public ZoneId getZone() {
            return START.getZone();
        }

        @Override
        public Clock withZone(ZoneId zone) {
            throw new UnsupportedOperationException();
        }

        @Override
        public synchronized Instant instant() {
            if (failing) {
                throw new IllegalStateException("clock unavailable");
            }
            Instant t = now;
            if (stepping) {
                now = t.plusSeconds(1);
            }
            return t;
        }

        void reset() {
            stepping = false;
            failing = false;
            now = START.toInstant();
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestClockConfig {
        @Bean
        @Primary
        Clock testClock() {
            return CLOCK;
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

    /** Each test is its own requester, so the rate limit of one test does not reach another. */
    final String appId = "test-" + UUID.randomUUID();

    @AfterEach
    void resetClock() {
        CLOCK.reset();
    }

    @AfterAll
    static void deleteQueues() {
        if (cleanup != null) {
            for (String q : new String[] {InquiryQueues.DATE_REQUEST, InquiryQueues.DATE_REPLY, InquiryQueues.ERROR,
                InquiryQueues.DEAD_LETTER}) {
                cleanup.deleteQueue(q + SUFFIX);
            }
        }
    }

    Message request(byte[] body, String messageId, String correlationId, String replyTo) {
        cleanup = admin;
        MessageProperties p = new MessageProperties();
        p.setMessageId(messageId);
        if (correlationId != null) {
            p.setCorrelationId(correlationId);
        }
        p.setReplyTo(replyTo);
        p.setAppId(appId);
        Message m = new Message(body, p);
        rabbit.send("", queues.dateRequest(), m);
        return m;
    }

    String request(String replyTo) {
        String id = "M-" + UUID.randomUUID();
        request(ascii("DATE"), id, null, replyTo);
        return id;
    }

    /**
     * Publishes the requests in order on one channel: the broker keeps publish order only within a channel, and
     * the template's cached channels may otherwise alternate between sends.
     */
    List<String> requestsInOrder(int count, String replyTo) {
        return rabbit.invoke(t -> {
            List<String> ids = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                ids.add(request(replyTo));
            }
            return ids;
        });
    }

    static byte[] ascii(String s) {
        return s.getBytes(StandardCharsets.US_ASCII);
    }

    static String body(Message m) {
        return new String(m.getBody(), StandardCharsets.US_ASCII);
    }

    /** A requester's private reply queue: exclusive to this connection, not auto-deleted between receives. */
    String tempQueue() {
        return admin.declareQueue(new Queue("test.reply." + UUID.randomUUID(), false, true, false));
    }

    Message reply(String queue) {
        Message m = rabbit.receive(queue, 15_000);
        assertThat(m).as("reply on " + queue).isNotNull();
        return m;
    }

    static void assertDateReply(Message m) {
        assertDateReply(m, EXPECTED);
    }

    static void assertDateReply(Message m, String expected) {
        String text = body(m);
        assertThat(text).hasSize(1000).startsWith(expected);
        assertThat(text.substring(expected.length())).isBlank();
    }

    /** The message on a shared queue (fixed reply queue, error queue, DLQ) that belongs to this test, skipping others. */
    Message onSharedQueue(String queue, Predicate<Message> mine) {
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

    /** The error record laid out per MQ-ERR-DISPLAY plus the storage code field (Q-COACCT01-11), padded to 1000. */
    static String record(String message, String cc, String rc, String queue) {
        String q = queue.length() > 48 ? queue.substring(0, 48) : queue;
        return (" ".repeat(25) + "  " + String.format("%-25s", message) + "  " + cc + "  " + rc + "  " + String.format("%-48s", q))
            .stripTrailing();
    }

    static void assertRecord(Message m, String expected) {
        String text = body(m);
        assertThat(text).hasSize(1000);
        assertThat(text.stripTrailing()).isEqualTo(expected);
    }

    @Test
    @Covers({"TS-CODATE01-01", "TS-CODATE01-08", "BR-CODATE01-06", "BR-CODATE01-07", "BR-CODATE01-08", "Q-CODATE01-04",
        "Q-CODATE01-05"})
    void request_gets_one_date_reply_on_its_reply_to_queue_correlated_by_message_id() {
        String replyTo = tempQueue();
        String id = "M1-" + UUID.randomUUID();
        request(ascii("DATE"), id, "C1", replyTo);
        Message m = reply(replyTo);
        assertDateReply(m);
        assertThat(m.getMessageProperties().getCorrelationId()).isEqualTo(id);
        assertThat(m.getMessageProperties().getMessageId()).isNotBlank().isNotEqualTo(id);
        assertThat(m.getMessageProperties().getType()).isEqualTo("reply");
        assertThat(m.getMessageProperties().getContentType()).isEqualTo("text/plain");
        assertThat(rabbit.receive(replyTo, 1_000)).as("exactly one reply").isNull();

        // TS-CODATE01-08 (fixed by Q-CODATE01-05): with no message id the requester's own correlation id is kept.
        request(ascii("DATE"), null, "OWN-CORRELATION", replyTo);
        assertThat(reply(replyTo).getMessageProperties().getCorrelationId()).isEqualTo("OWN-CORRELATION");
    }

    @Test
    @Covers({"TS-CODATE01-02", "TS-CODATE01-03", "BR-CODATE01-05", "VR-CODATE01-01", "Q-CODATE01-02"})
    void empty_and_account_requests_also_get_a_date_reply() {
        String replyTo = tempQueue();
        request(new byte[0], "E-" + UUID.randomUUID(), null, replyTo);
        assertDateReply(reply(replyTo));
        request(ascii("INQA00000000001"), "A-" + UUID.randomUUID(), null, replyTo);
        assertDateReply(reply(replyTo));
    }

    @Test
    @Covers({"TS-CODATE01-05", "Q-CODATE01-15"})
    void each_queued_request_carries_the_time_it_was_processed() {
        String replyTo = tempQueue();
        CLOCK.stepping = true;
        List<String> ids = requestsInOrder(2, replyTo);
        Message first = reply(replyTo);
        Message second = reply(replyTo);
        assertThat(first.getMessageProperties().getCorrelationId()).isEqualTo(ids.get(0));
        assertDateReply(first, "SYSTEM DATE : 03-21-2024SYSTEM TIME : 14:05:09");
        assertDateReply(second, "SYSTEM DATE : 03-21-2024SYSTEM TIME : 14:05:10");
    }

    @Test
    @Covers({"TS-CODATE01-06", "BR-CODATE01-09"})
    void queued_requests_are_answered_in_queue_order() {
        String replyTo = tempQueue();
        List<String> ids = requestsInOrder(3, replyTo);
        for (String id : ids) {
            Message m = reply(replyTo);
            assertThat(m.getMessageProperties().getCorrelationId()).isEqualTo(id);
            assertDateReply(m);
        }
    }

    @Test
    @Covers({"TS-CODATE01-07", "BR-CODATE01-02", "Q-CODATE01-04"})
    void request_without_reply_to_is_answered_on_the_fixed_reply_queue() {
        String id = "F-" + UUID.randomUUID();
        request(ascii("DATE"), id, null, null);
        Message m = onSharedQueue(queues.dateReply(), x -> id.equals(x.getMessageProperties().getCorrelationId()));
        assertDateReply(m);
    }

    @Test
    @Covers({"TS-CODATE01-09", "VR-CODATE01-02", "Q-CODATE01-07"})
    void oversized_request_gets_a_normal_date_reply() {
        String replyTo = tempQueue();
        request(new byte[1200], "O-" + UUID.randomUUID(), null, replyTo);
        assertDateReply(reply(replyTo));
        request(new byte[] {(byte) 0xC4, (byte) 0xC1, 0x00, (byte) 0xFF}, "U-" + UUID.randomUUID(), null, replyTo);
        assertDateReply(reply(replyTo));
        assertThat(rabbit.receive(queues.deadLetter(), 1_000)).isNull();
    }

    @Test
    @Covers({"TS-CODATE01-12", "ERR-CODATE01-04", "ERR-CODATE01-06", "Q-CODATE01-06"})
    void reply_to_a_queue_that_does_not_exist_is_reported_on_the_error_queue() {
        String missing = "test.missing." + UUID.randomUUID();
        String id = request(missing);
        assertRecord(errorRecordFor(id), record("MQPUT ERR", "02", "02085", missing));
        String replyTo = tempQueue();
        request(replyTo);
        assertDateReply(reply(replyTo));
    }

    @Test
    @Covers({"TS-CODATE01-13", "ERR-CODATE01-06", "Q-CODATE01-06", "Q-CODATE01-12"})
    void full_reply_queue_is_reported_on_the_error_queue_and_the_request_is_consumed() {
        Map<String, Object> args = new HashMap<>();
        args.put("x-max-length", 1);
        args.put("x-overflow", "reject-publish");
        String full = admin.declareQueue(new Queue("test.full." + UUID.randomUUID(), false, true, false, args));
        rabbit.send("", full, new Message(ascii("occupied")));
        String id = request(full);
        assertRecord(errorRecordFor(id), record("MQPUT ERR", "02", "02053", full));
        assertThat(body(rabbit.receive(full, 1_000))).isEqualTo("occupied");
        assertThat(rabbit.receive(full, 1_000)).as("no reply fits").isNull();
        assertThat(rabbit.receive(queues.deadLetter(), 1_000)).as("the request was consumed").isNull();

        // Q-CODATE01-12: the next error record is built fresh, with this failure's queue only.
        String missing = "test.missing." + UUID.randomUUID();
        assertRecord(errorRecordFor(request(missing)), record("MQPUT ERR", "02", "02085", missing));
    }

    @Test
    @Covers({"TS-CODATE01-11", "ERR-CODATE01-07", "ERR-CODATE01-01"})
    void an_error_record_the_error_queue_cannot_take_sends_the_request_to_the_dead_letter_queue() {
        admin.deleteQueue(queues.error());
        String poison;
        try {
            poison = request("test.missing." + UUID.randomUUID());
            Message dead = onSharedQueue(queues.deadLetter(), m -> poison.equals(m.getMessageProperties().getMessageId()));
            assertThat(body(dead)).isEqualTo("DATE");
        } finally {
            admin.declareQueue(new Queue(queues.error(), true));
        }
        String replyTo = tempQueue();
        request(replyTo);
        assertDateReply(reply(replyTo));
    }

    @Test
    @Covers({"ERR-CODATE01-09", "ERR-CODATE01-10", "Q-CODATE01-07"})
    void a_request_that_fails_three_deliveries_is_dead_lettered_and_the_next_request_is_answered() {
        String replyTo = tempQueue();
        CLOCK.failing = true;
        String poison;
        try {
            poison = request(replyTo);
            Message dead = onSharedQueue(queues.deadLetter(), m -> poison.equals(m.getMessageProperties().getMessageId()));
            assertThat(body(dead)).isEqualTo("DATE");
        } finally {
            CLOCK.failing = false;
        }
        assertThat(rabbit.receive(replyTo, 1_000)).as("the poison request got no reply").isNull();
        String next = request(replyTo);
        Message ok = reply(replyTo);
        assertThat(ok.getMessageProperties().getCorrelationId()).isEqualTo(next);
        assertDateReply(ok);
    }

    @Test
    @Covers({"Q-CODATE01-08"})
    void a_closed_broker_connection_is_recovered_and_the_next_request_is_answered() {
        connections.resetConnection();
        String replyTo = tempQueue();
        String id = request(replyTo);
        Message m = rabbit.receive(replyTo, 30_000);
        assertThat(m).as("reply after the connection was closed").isNotNull();
        assertThat(m.getMessageProperties().getCorrelationId()).isEqualTo(id);
        assertDateReply(m);
        assertThat(admin.getQueueInfo(queues.dateRequest()).getConsumerCount()).as("consumer back").isPositive();
    }

    @Test
    @Covers({"BR-CODATE01-01", "BR-CODATE01-03", "Q-CODATE01-16", "Q-CODATE01-09"})
    void date_queues_are_declared_at_startup_with_a_long_lived_consumer() {
        for (String q : List.of(queues.dateRequest(), queues.dateReply(), queues.error(), queues.deadLetter())) {
            assertThat(admin.getQueueInfo(q)).as(q).isNotNull();
        }
        assertThat(admin.getQueueInfo(queues.dateRequest()).getConsumerCount()).as("long-lived consumer").isPositive();
    }

    @Test
    @Covers({"TS-CODATE01-14", "NAV-CODATE01-01", "BR-CODATE01-04", "BR-CODATE01-11", "Q-CODATE01-16"})
    void the_listener_stays_up_after_an_idle_period() throws InterruptedException {
        String replyTo = tempQueue();
        Thread.sleep(6_000);
        request(replyTo);
        assertDateReply(reply(replyTo));
    }

    @Test
    @Covers({"Q-CODATE01-14"})
    void a_requester_over_its_rate_limit_gets_an_error_reply() {
        String replyTo = tempQueue();
        List<String> ids = requestsInOrder(RATE_LIMIT + 1, replyTo);
        List<String> types = new ArrayList<>();
        for (int i = 0; i < RATE_LIMIT + 1; i++) {
            Message m = reply(replyTo);
            types.add(m.getMessageProperties().getType());
            if ("error".equals(m.getMessageProperties().getType())) {
                assertRecord(m, record("REQUEST RATE EXCEEDED", "02", "00000", queues.dateRequest()));
                assertThat(m.getMessageProperties().getCorrelationId()).isEqualTo(ids.get(RATE_LIMIT));
            }
        }
        List<String> expected = new ArrayList<>(Collections.nCopies(RATE_LIMIT, "reply"));
        expected.add("error");
        assertThat(types).isEqualTo(expected);
        assertThat(errorRecordFor(ids.get(RATE_LIMIT))).isNotNull();
    }

    @Test
    @Covers({"Q-CODATE01-03", "Q-CODATE01-01", "C-ROLE"})
    void rest_returns_iso_time_with_offset_to_a_signed_on_user_only() throws Exception {
        assertThat(mvc.perform(get("/api/inquiry/date")).andReturn().getResponse().getStatus()).isEqualTo(401);
        sample.load();
        ScreenClient client = new ScreenClient(mvc);
        client.signOn("USER0001");
        String body = mvc.perform(get("/api/inquiry/date").session(client.session())).andReturn().getResponse().getContentAsString();
        assertThat(body).isEqualTo("{\"timestamp\":\"2024-03-21T14:05:09-05:00\"}");
    }
}
