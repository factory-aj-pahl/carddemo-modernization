package com.carddemo.account.web;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.carddemo.test.Covers;
import com.carddemo.test.SampleData;
import com.carddemo.test.ScreenClient;
import com.carddemo.test.TestApplication;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

/** 2000-DECIDE-ACTION with a conversation state it has no branch for (ERR-COACTUPC-12). */
@SpringBootTest(classes = TestApplication.class)
@AutoConfigureMockMvc
class AccountUpdateStateTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    SampleData sample;

    @Test
    @Covers({"ERR-COACTUPC-12"})
    void an_unexpected_conversation_state_fails_the_turn() {
        sample.load();
        ScreenClient client = new ScreenClient(mvc);
        client.signOn("ADMIN001");
        client.show("COACTUPC");
        client.session().setAttribute(AccountUpdateController.CONVERSATION_ATTR, new AccountUpdateController.Conversation());
        assertThatThrownBy(() -> client.post("COACTUPC", "ENTER", Map.of("fields", Map.of("accountId", "00000000024"))))
            .as("the turn ends without a screen").hasRootCauseInstanceOf(RuntimeException.class);
    }
}
