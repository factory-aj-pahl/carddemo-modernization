package com.carddemo.transaction;

import static org.assertj.core.api.Assertions.assertThat;

import com.carddemo.common.msg.CommonMessages;
import com.carddemo.test.Covers;
import com.carddemo.test.ScreenClient;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Empty-input turns, continuing conversations, and the lock-free view of COTRN00C, COTRN01C, and COTRN02C. */
class TransactionTurnRulesTest extends TransactionScreenTestBase {

    static final String FIRST = "0000000000683580";

    @Autowired
    PlatformTransactionManager txm;

    @Test
    @Covers({"ERR-COTRN00C-08"})
    void a_list_turn_with_no_fields_is_handled_as_a_blank_search() {
        client.signOn(ADMIN);
        client.show("COTRN00C");
        JsonNode r = client.post("COTRN00C", "ENTER", Map.of());
        assertThat(r.has("message")).isFalse();
        assertThat(r.at("/fields/page").asText()).isEqualTo("00000001");
        assertThat(r.at("/fields/rows/0/tranId").asText()).isEqualTo(FIRST);
    }

    @Test
    @Covers({"NAV-COTRN00C-09", "C-SESSION"})
    void each_list_turn_continues_from_the_position_kept_by_the_previous_one() {
        client.signOn(ADMIN);
        client.show("COTRN00C");
        assertThat(client.post("COTRN00C", "PF8", Map.of("searchTranId", "")).at("/fields/page").asText()).isEqualTo("00000002");
        JsonNode invalid = client.post("COTRN00C", "PF5", Map.of("searchTranId", ""));
        assertThat(invalid.at("/message").asText()).isEqualTo(CommonMessages.INVALID_KEY);
        assertThat(invalid.at("/fields/page").asText()).isEqualTo("00000002");
        JsonNode p3 = client.post("COTRN00C", "PF8", Map.of("searchTranId", ""));
        assertThat(p3.at("/fields/page").asText()).isEqualTo("00000003");
        assertThat(p3.at("/fields/rows/0/tranId").asText()).isEqualTo(allIds().get(20));
    }

    @Test
    @Covers({"ERR-COTRN01C-02"})
    void a_view_turn_with_no_fields_is_handled_as_a_blank_id() {
        client.signOn(ADMIN);
        client.show("COTRN01C");
        JsonNode r = client.post("COTRN01C", "ENTER", Map.of());
        assertThat(r.at("/message").asText()).isEqualTo("Tran ID can NOT be empty...");
        assertThat(r.at("/focus").asText()).isEqualTo("tranIdIn");
    }

    @Test
    @Covers({"NAV-COTRN01C-09", "C-SESSION"})
    void after_a_view_redisplay_the_next_turn_fetches_and_back_still_returns_to_the_caller() {
        client.signOn(ADMIN);
        client.show("COTRN01C");
        assertThat(client.post("COTRN01C", "ENTER", Map.of("tranIdIn", "9999999999999999")).at("/message").asText())
            .isEqualTo("Transaction ID NOT found...");
        JsonNode found = client.post("COTRN01C", "ENTER", Map.of("tranIdIn", FIRST));
        assertThat(found.at("/fields/tranId").asText()).isEqualTo(FIRST);
        assertThat(client.post("COTRN01C", "PF3", Map.of()).at("/navigate").asText()).isEqualTo("COMEN01C");
    }

    @Test
    @Covers({"Q-COTRN01C-01"})
    void viewing_takes_no_lock_so_a_row_held_for_update_can_still_be_viewed() throws Exception {
        client.signOn(ADMIN);
        client.show("COTRN01C");
        ExecutorService ex = Executors.newFixedThreadPool(2);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            Future<?> holder = ex.submit(() -> new TransactionTemplate(txm).executeWithoutResult(st -> {
                jdbc.queryForList("SELECT tran_id FROM card_transaction WITH (UPDLOCK, ROWLOCK) WHERE tran_id = ?", FIRST);
                locked.countDown();
                try {
                    release.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                st.setRollbackOnly();
            }));
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
            ScreenClient second = new ScreenClient(mvc);
            second.signOn(ADMIN);
            Future<JsonNode> view = ex.submit(() -> second.post("COTRN01C", "ENTER", Map.of("tranIdIn", FIRST)));
            JsonNode r = view.get(5, TimeUnit.SECONDS);
            assertThat(r.at("/fields/tranId").asText()).isEqualTo(FIRST);
            assertThat(client.post("COTRN01C", "ENTER", Map.of("tranIdIn", FIRST)).at("/fields/tranId").asText())
                .as("a second viewer is not serialized behind the first").isEqualTo(FIRST);
            release.countDown();
            holder.get(10, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            ex.shutdownNow();
        }
    }

    @Test
    @Covers({"ERR-COTRN02C-09"})
    void an_add_turn_with_no_fields_is_handled_as_blank_input() {
        client.signOn(ADMIN);
        client.show("COTRN02C");
        int before = transactionCount();
        JsonNode r = client.post("COTRN02C", "ENTER", Map.of());
        assertThat(r.at("/message").asText()).isEqualTo("Account or Card Number must be entered...");
        assertThat(r.at("/focus").asText()).isEqualTo("accountId");
        assertThat(transactionCount()).isEqualTo(before);
    }

    @Test
    @Covers({"NAV-COTRN02C-09", "C-SESSION"})
    void after_an_add_redisplay_the_next_turn_continues_with_the_held_card() {
        client.signOn(ADMIN);
        client.show("COTRN02C");
        JsonNode first = client.post("COTRN02C", "ENTER", Map.of("accountId", "00000000001"));
        assertThat(first.has("message")).isTrue();
        String masked = first.at("/fields/cardNum").asText();
        assertThat(masked).startsWith("************");
        JsonNode second = client.post("COTRN02C", "ENTER", Map.of("accountId", "00000000001", "cardNum", masked));
        assertThat(second.at("/fields/cardNum").asText()).as("the held card is used for the masked value").isEqualTo(masked);
        assertThat(second.at("/message").asText()).isEqualTo(first.at("/message").asText());
    }
}
