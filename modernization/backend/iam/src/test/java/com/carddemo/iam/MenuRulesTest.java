package com.carddemo.iam;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.reset;

import com.carddemo.common.msg.CommonMessages;
import com.carddemo.iam.internal.MenuCatalog;
import com.carddemo.test.Covers;
import com.carddemo.test.SampleData;
import com.carddemo.test.ScreenClient;
import com.carddemo.test.TestApplication;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

/** COMEN01C and COADM01C dispatch, redisplay, and role rules. */
@SpringBootTest(classes = TestApplication.class)
@AutoConfigureMockMvc
class MenuRulesTest {

    static final String BAD_OPTION = "Please enter a valid option number...";

    @Autowired
    MockMvc mvc;
    @Autowired
    SampleData sample;
    @MockitoSpyBean
    MenuCatalog catalog;

    ScreenClient client;

    @BeforeEach
    void load() {
        sample.load();
        client = new ScreenClient(mvc);
    }

    @AfterEach
    void unstub() {
        reset(catalog);
    }

    /** A test copy of the main-menu table in which option 8 (Transaction Add) is restricted to admins. */
    void option8AdminOnly() {
        List<MenuCatalog.Option> copy = new ArrayList<>(MenuCatalog.MAIN);
        MenuCatalog.Option o = copy.get(7);
        copy.set(7, new MenuCatalog.Option(o.number(), o.name(), o.program(), "A"));
        doReturn(List.copyOf(copy)).when(catalog).main();
    }

    @Test
    @Covers({"TS-COMEN01C-11", "BR-COMEN01C-05", "VR-COMEN01C-02", "Q-COMEN01C-01"})
    void an_admin_only_option_is_refused_to_a_user_and_open_to_an_admin() {
        option8AdminOnly();
        client.signOn("USER0001");
        JsonNode r = client.post("COMEN01C", "ENTER", Map.of("option", "8"));
        assertThat(r.at("/message").asText()).isEqualTo("No access - Admin Only option... ");
        assertThat(r.has("navigate")).isFalse();
        assertThat(r.at("/fields/options")).hasSize(11);
        assertThat(client.post("COMEN01C", "ENTER", Map.of("option", "7")).at("/navigate").asText()).isEqualTo("COTRN01C");

        ScreenClient admin = new ScreenClient(mvc);
        admin.signOn("ADMIN001");
        assertThat(admin.post("COMEN01C", "ENTER", Map.of("option", "8")).at("/navigate").asText()).isEqualTo("COTRN02C");
    }

    @Test
    @Covers({"NAV-COMEN01C-01"})
    void enter_dispatches_the_selected_main_menu_option() {
        client.signOn("USER0001");
        assertThat(client.post("COMEN01C", "ENTER", Map.of("option", "01")).at("/navigate").asText()).isEqualTo("COACTVWC");
        assertThat(client.post("COMEN01C", "ENTER", Map.of("option", "9")).at("/navigate").asText()).isEqualTo("CORPT00C");
    }

    @Test
    @Covers({"ERR-COMEN01C-03"})
    void a_main_menu_turn_with_no_fields_is_handled_as_blank_input() {
        client.signOn("USER0001");
        JsonNode r = client.post("COMEN01C", "ENTER", Map.of());
        assertThat(r.at("/message").asText()).isEqualTo(BAD_OPTION);
        assertThat(r.has("navigate")).isFalse();
    }

    @Test
    @Covers({"NAV-COMEN01C-05", "C-SESSION"})
    void after_a_main_menu_redisplay_the_next_turn_continues_with_the_same_user() {
        client.signOn("USER0001");
        assertThat(client.post("COMEN01C", "ENTER", Map.of("option", "99")).at("/message").asText()).isEqualTo(BAD_OPTION);
        assertThat(client.post("COMEN01C", "PF7", Map.of("option", "2")).at("/message").asText())
            .isEqualTo(CommonMessages.INVALID_KEY);
        assertThat(client.post("COMEN01C", "ENTER", Map.of("option", "2")).at("/navigate").asText()).isEqualTo("COACTUPC");
        assertThat(client.show("COMEN01C").at("/fields/options")).as("still the USER session").hasSize(11);
    }

    @Test
    @Covers({"NAV-COADM01C-01"})
    void enter_dispatches_the_selected_admin_menu_option() {
        client.signOn("ADMIN001");
        assertThat(client.post("COADM01C", "ENTER", Map.of("option", "1")).at("/navigate").asText()).isEqualTo("COUSR00C");
        assertThat(client.post("COADM01C", "ENTER", Map.of("option", "06")).at("/navigate").asText()).isEqualTo("COTRTUPC");
    }

    @Test
    @Covers({"ERR-COADM01C-03"})
    void an_admin_menu_turn_with_no_fields_is_handled_as_blank_input() {
        client.signOn("ADMIN001");
        JsonNode r = client.post("COADM01C", "ENTER", Map.of());
        assertThat(r.at("/message").asText()).isEqualTo(BAD_OPTION);
        assertThat(r.has("navigate")).isFalse();
    }

    @Test
    @Covers({"NAV-COADM01C-05", "C-SESSION"})
    void after_an_admin_menu_redisplay_the_next_turn_continues_with_the_same_user() {
        client.signOn("ADMIN001");
        assertThat(client.post("COADM01C", "ENTER", Map.of("option", "7")).at("/message").asText()).isEqualTo(BAD_OPTION);
        assertThat(client.post("COADM01C", "ENTER", Map.of("option", "2")).at("/navigate").asText()).isEqualTo("COUSR01C");
        assertThat(client.showRaw("COUSR01C").getStatus()).as("still the ADMIN session").isEqualTo(200);
    }
}
