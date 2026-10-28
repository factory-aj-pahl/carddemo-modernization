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
import java.time.ZoneId;
import java.time.ZonedDateTime;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/** CODATE01 over RabbitMQ (real broker, private queues) and over REST, at the spec's clock 2024-03-21 14:05:09. */
@SpringBootTest(classes = {TestApplication.class, DateInquiryMessagingTest.FixedClock.class})
@AutoConfigureMockMvc
class DateInquiryMessagingTest {

    static final String SUFFIX = ".test-" + UUID.randomUUID();
    static final String EXPECTED = "SYSTEM DATE : 03-21-2024SYSTEM TIME : 14:05:09";
    static RabbitAdmin cleanup;

    @TestConfiguration(proxyBeanMethods = false)
    static class FixedClock {
        @Bean
        @Primary
        Clock fixedClock() {
            ZonedDateTime t = ZonedDateTime.of(2024, 3, 21, 14, 5, 9, 0, ZoneId.of("America/Chicago"));
            return Clock.fixed(t.toInstant(), t.getZone());
        }
    }

    @DynamicPropertySource
    static void privateQueues(DynamicPropertyRegistry r) {
        r.add("carddemo.mq.queue-suffix", () -> SUFFIX);
        r.add("spring.rabbitmq.listener.simple.auto-startup", () -> "true");
    }

    @Autowired
    RabbitTemplate rabbit;
    @Autowired
    RabbitAdmin admin;
    @Autowired
    InquiryQueues queues;
    @Autowired
    MockMvc mvc;
    @Autowired
    SampleData sample;

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
        Message m = new Message(body, p);
        rabbit.send("", queues.dateRequest(), m);
        return m;
    }

    /** A requester's private reply queue: exclusive to this connection, not auto-deleted between receives. */
    String tempQueue() {
        return admin.declareQueue(new Queue("test.reply." + UUID.randomUUID(), false, true, false));
    }

    Message reply(String queue) {
        Message m = rabbit.receive(queue, 10_000);
        assertThat(m).as("reply on " + queue).isNotNull();
        return m;
    }

    static void assertDateReply(Message m) {
        String text = new String(m.getBody(), StandardCharsets.US_ASCII);
        assertThat(text).hasSize(1000).startsWith(EXPECTED);
        assertThat(text.substring(EXPECTED.length())).isBlank();
    }

    @Test
    @Covers({"TS-CODATE01-01", "BR-CODATE01-06", "BR-CODATE01-07", "BR-CODATE01-08", "Q-CODATE01-04", "Q-CODATE01-05"})
    void request_gets_one_date_reply_on_its_reply_to_queue_correlated_by_message_id() {
        String replyTo = tempQueue();
        String id = "M1-" + UUID.randomUUID();
        request("DATE".getBytes(StandardCharsets.US_ASCII), id, "C1", replyTo);
        Message m = reply(replyTo);
        assertDateReply(m);
        assertThat(m.getMessageProperties().getCorrelationId()).isEqualTo(id);
        assertThat(m.getMessageProperties().getMessageId()).isNotBlank().isNotEqualTo(id);
        assertThat(m.getMessageProperties().getType()).isEqualTo("reply");
        assertThat(rabbit.receive(replyTo, 1_000)).as("exactly one reply").isNull();
    }

    @Test
    @Covers({"TS-CODATE01-02", "TS-CODATE01-03", "BR-CODATE01-05", "VR-CODATE01-01", "Q-CODATE01-02"})
    void empty_and_account_requests_also_get_a_date_reply() {
        String replyTo = tempQueue();
        request(new byte[0], "E-" + UUID.randomUUID(), null, replyTo);
        assertDateReply(reply(replyTo));
        request("INQA00000000001".getBytes(StandardCharsets.US_ASCII), "A-" + UUID.randomUUID(), null, replyTo);
        assertDateReply(reply(replyTo));
    }

    @Test
    @Covers({"TS-CODATE01-07", "BR-CODATE01-02", "Q-CODATE01-04"})
    void request_without_reply_to_is_answered_on_the_fixed_reply_queue() {
        String id = "F-" + UUID.randomUUID();
        request("DATE".getBytes(StandardCharsets.US_ASCII), id, null, null);
        Message m = reply(queues.dateReply());
        assertDateReply(m);
        assertThat(m.getMessageProperties().getCorrelationId()).isEqualTo(id);
    }

    @Test
    @Covers({"TS-CODATE01-09", "Q-CODATE01-07"})
    void oversized_request_gets_a_normal_date_reply() {
        String replyTo = tempQueue();
        request(new byte[1200], "O-" + UUID.randomUUID(), null, replyTo);
        assertDateReply(reply(replyTo));
    }

    @Test
    @Covers({"Q-CODATE01-03", "C-ROLE"})
    void rest_returns_iso_time_with_offset_to_a_signed_on_user_only() throws Exception {
        assertThat(mvc.perform(get("/api/inquiry/date")).andReturn().getResponse().getStatus()).isEqualTo(401);
        sample.load();
        ScreenClient client = new ScreenClient(mvc);
        client.signOn("USER0001");
        String body = mvc.perform(get("/api/inquiry/date").session(client.session())).andReturn().getResponse().getContentAsString();
        assertThat(body).isEqualTo("{\"timestamp\":\"2024-03-21T14:05:09-05:00\"}");
    }
}
