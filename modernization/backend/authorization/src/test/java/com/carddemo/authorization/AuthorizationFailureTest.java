package com.carddemo.authorization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.carddemo.authorization.internal.AuthorizationEngine;
import com.carddemo.authorization.internal.PendingAuthRepository;
import com.carddemo.authorization.messaging.AuthQueues;
import com.carddemo.card.api.CardDirectory;
import com.carddemo.test.Covers;
import com.carddemo.test.SampleData;
import com.carddemo.test.ScreenClient;
import com.carddemo.test.TestApplication;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * COPAUA0C critical errors on both transports: the message's transaction rolls back (Q-COPAUA0C-07), one
 * critical CCPAUERY record is kept, no reply is sent (the request is dead-lettered; REST answers 503).
 */
@SpringBootTest(classes = TestApplication.class)
@AutoConfigureMockMvc
class AuthorizationFailureTest {

    static final String SUFFIX = ".test-" + UUID.randomUUID();
    static RabbitAdmin cleanup;

    @DynamicPropertySource
    static void privateQueues(DynamicPropertyRegistry r) {
        r.add("carddemo.mq.queue-suffix", () -> SUFFIX);
        r.add("spring.rabbitmq.listener.simple.auto-startup", () -> "true");
    }

    @MockitoSpyBean
    PendingAuthRepository repo;
    @MockitoSpyBean
    CardDirectory cards;
    @MockitoSpyBean
    PlatformTransactionManager tm;
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

    ScreenClient adminClient;
    final ObjectMapper json = new ObjectMapper();

    @AfterAll
    static void deleteQueues() {
        if (cleanup != null) {
            for (String q : new String[] {AuthQueues.REQUEST, AuthQueues.REPLY, AuthQueues.DEAD_LETTER}) {
                cleanup.deleteQueue(q + SUFFIX);
            }
        }
    }

    @BeforeEach
    void load() {
        cleanup = admin;
        AuthFixtures.load(sample, jdbc);
        adminClient = new ScreenClient(mvc);
        adminClient.signOn("ADMIN001");
    }

    @AfterEach
    void unstub() {
        reset(repo, cards, tm);
    }

    enum Transport { QUEUE, REST }

    /** Runs the example request; asserts no reply (QUEUE: dead-lettered; REST: 503 naming the location). */
    void expectFailure(Transport t, String location) throws Exception {
        if (t == Transport.QUEUE) {
            String replyTo = admin.declareQueue(new Queue("test.reply." + UUID.randomUUID(), false, true, false));
            String id = "X-" + UUID.randomUUID();
            MessageProperties p = new MessageProperties();
            p.setMessageId(id);
            p.setReplyTo(replyTo);
            rabbit.send("", queues.request(), new Message(AuthFixtures.EXAMPLE.getBytes(StandardCharsets.US_ASCII), p));
            Message dead = rabbit.receive(queues.deadLetter(), 15_000);
            assertThat(dead).as("dead-lettered").isNotNull();
            assertThat(dead.getMessageProperties().getMessageId()).isEqualTo(id);
            assertThat(rabbit.receive(replyTo, 500)).as("no reply").isNull();
        } else {
            Map<String, Object> body = new LinkedHashMap<>();
            String[] f = AuthFixtures.EXAMPLE.split(",", -1);
            String[] names = {"authDate", "authTime", "cardNum", "authType", "cardExpiryDate", "messageType", "messageSource",
                "processingCode", "transactionAmt", "merchantCategoryCode", "acqrCountryCode", "posEntryMode", "merchantId",
                "merchantName", "merchantCity", "merchantState", "merchantZip", "transactionId"};
            for (int i = 0; i < names.length; i++) {
                body.put(names[i], f[i]);
            }
            MockHttpServletResponse res = mvc.perform(post("/api/authorizations").session(adminClient.session()).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body))).andReturn().getResponse();
            assertThat(res.getStatus()).isEqualTo(503);
            assertThat(res.getContentAsString()).contains("\"location\":\"" + location + "\"");
        }
    }

    void assertUnchanged(String location, String level, String subsystem, String message) {
        assertThat(jdbc.queryForObject("SELECT approved_count FROM pending_auth_summary WHERE account_id = 7", Integer.class)).isEqualTo(50);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pending_auth_detail WHERE account_id = 7", Integer.class)).isEqualTo(50);
        List<Map<String, Object>> log = jdbc.queryForList("SELECT * FROM auth_error_log WHERE err_level = 'C'");
        assertThat(log).singleElement().satisfies(e -> assertThat(e).containsEntry("location", location)
            .containsEntry("err_level", level).containsEntry("subsystem", subsystem).containsEntry("message", message)
            .containsEntry("event_key", location.startsWith("C002") ? "00000000007"
                : location.startsWith("C003") ? "000000007" : "************7065"));
    }

    void clearLog() {
        jdbc.update("DELETE FROM auth_error_log");
    }

    @Test
    @Covers({"TS-COPAUA0C-10", "ERR-COPAUA0C-03", "BR-COPAUA0C-19"})
    void transaction_that_cannot_start_logs_i001_and_sends_no_reply() throws Exception {
        for (Transport t : Transport.values()) {
            clearLog();
            doThrow(new CannotCreateTransactionException("database unavailable")).when(tm)
                .getTransaction(argThat(d -> d != null && AuthorizationEngine.TRANSACTION_NAME.equals(d.getName())));
            expectFailure(t, "I001");
            assertUnchanged("I001", "C", "I", "IMS SCHD FAILED");
            reset(tm);
        }
    }

    @Test
    @Covers({"ERR-COPAUA0C-04"})
    void xref_read_failure_logs_c001() throws Exception {
        for (Transport t : Transport.values()) {
            clearLog();
            doThrow(new DataAccessResourceFailureException("xref down")).when(cards).findXref(any());
            expectFailure(t, "C001");
            assertUnchanged("C001", "C", "C", "FAILED TO READ XREF FILE");
            reset(cards);
        }
    }

    @Test
    @Covers({"ERR-COPAUA0C-05", "ERR-COPAUA0C-06"})
    void account_and_customer_read_failures_log_c002_and_c003() throws Exception {
        for (Transport t : Transport.values()) {
            clearLog();
            doThrow(new DataAccessResourceFailureException("acct down")).when(repo).account(anyLong());
            expectFailure(t, "C002");
            assertUnchanged("C002", "C", "C", "FAILED TO READ ACCT FILE");
            reset(repo);
            clearLog();
            doThrow(new DataAccessResourceFailureException("cust down")).when(repo).customer(anyLong());
            expectFailure(t, "C003");
            assertUnchanged("C003", "C", "C", "FAILED TO READ CUST FILE");
            reset(repo);
        }
    }

    @Test
    @Covers({"ERR-COPAUA0C-07"})
    void summary_read_failure_logs_i002() throws Exception {
        for (Transport t : Transport.values()) {
            clearLog();
            doThrow(new DataAccessResourceFailureException("ims down")).when(repo).lockSummary(anyLong());
            expectFailure(t, "I002");
            assertUnchanged("I002", "C", "I", "IMS GET SUMMARY FAILED");
            reset(repo);
        }
    }

    @Test
    @Covers({"ERR-COPAUA0C-09", "Q-COPAUA0C-07"})
    void summary_update_failure_logs_i003_and_rolls_back() throws Exception {
        for (Transport t : Transport.values()) {
            clearLog();
            doThrow(new DataAccessResourceFailureException("repl failed")).when(repo).updateSummary(any());
            expectFailure(t, "I003");
            assertUnchanged("I003", "C", "I", "IMS UPDATE SUMRY FAILED");
            reset(repo);
        }
    }

    @Test
    @Covers({"ERR-COPAUA0C-10", "Q-COPAUA0C-07"})
    void detail_insert_failure_logs_i004_and_rolls_back_the_summary_update() throws Exception {
        for (Transport t : Transport.values()) {
            clearLog();
            doThrow(new org.springframework.dao.DuplicateKeyException("II")).when(repo).insertDetail(any());
            expectFailure(t, "I004");
            assertUnchanged("I004", "C", "I", "IMS INSERT DETL FAILED");
            reset(repo);
        }
    }

    @Test
    @Covers({"Q-COPAUA0C-06"})
    void commit_failure_sends_no_approval_and_dead_letters_the_request() throws Exception {
        for (Transport t : Transport.values()) {
            clearLog();
            doAnswer(inv -> {
                ((PlatformTransactionManager) inv.getMock()).rollback(inv.<TransactionStatus>getArgument(0));
                throw new TransactionSystemException("commit failed");
            }).when(tm).commit(argThat(s -> s != null && !s.isCompleted()
                && AuthorizationEngine.TRANSACTION_NAME.equals(TransactionSynchronizationManager.getCurrentTransactionName())));
            expectFailure(t, "I003");
            assertUnchanged("I003", "C", "I", "IMS UPDATE SUMRY FAILED");
            reset(tm);
        }
    }
}
