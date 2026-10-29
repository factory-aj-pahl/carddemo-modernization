package com.carddemo.iam;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

import com.carddemo.iam.internal.UserRepository;
import com.carddemo.test.Covers;
import com.carddemo.test.SampleData;
import com.carddemo.test.ScreenClient;
import com.carddemo.test.TestApplication;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/** COSGN00C lookup, verification, session, and redisplay rules with the approved sign-on fixes. */
@SpringBootTest(classes = TestApplication.class)
@AutoConfigureMockMvc
class SignonRulesTest {

    static final String INVALID = "Invalid User ID or Password. Try again ...";

    @Autowired
    MockMvc mvc;
    @Autowired
    SampleData sample;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    PasswordEncoder encoder;
    @MockitoSpyBean
    UserRepository users;

    ScreenClient client;

    @BeforeEach
    void load() {
        sample.load();
        client = new ScreenClient(mvc);
    }

    @AfterEach
    void unstub() {
        reset(users);
    }

    /** The signed-on user as the web app reads it (GET /api/session). */
    JsonNode session(ScreenClient c) throws Exception {
        String body = mvc.perform(MockMvcRequestBuilders.get("/api/session").session(c.session())).andReturn()
            .getResponse().getContentAsString();
        return new ObjectMapper().readTree(body);
    }

    @Test
    @Covers({"NAV-COSGN00C-01", "BR-COSGN00C-03", "BR-COSGN00C-05", "BR-COSGN00C-02", "Q-COSGN00C-02"})
    void enter_looks_up_the_upper_cased_id_and_signs_on_with_a_fresh_session() throws Exception {
        JsonNode r = client.post("COSGN00C", "ENTER", Map.of("userId", "user0001", "password", "PASSWORD"));
        assertThat(r.at("/navigate").asText()).isEqualTo("COMEN01C");
        JsonNode who = session(client);
        assertThat(who.at("/userId").asText()).isEqualTo("USER0001");
        assertThat(who.at("/role").asText()).isEqualTo("USER");
        JsonNode menu = client.show("COMEN01C");
        assertThat(menu.has("message")).as("context 0: the menu starts fresh").isFalse();
        assertThat(menu.at("/fields/option").asText()).isEmpty();

        JsonNode prefix = new ScreenClient(mvc).post("COSGN00C", "ENTER", Map.of("userId", "USER000", "password", "PASSWORD"));
        assertThat(prefix.at("/message").asText()).as("exact-key lookup, no partial match").isEqualTo(INVALID);
    }

    @Test
    @Covers({"BR-COSGN00C-04", "VR-COSGN00C-03", "BR-COSGN00C-08", "Q-COSGN00C-01", "Q-COSGN00C-04"})
    void the_password_is_verified_against_a_hash_and_a_mismatch_keeps_the_user_id_with_focus_on_password() {
        String stored = jdbc.queryForObject("SELECT password_hash FROM app_user WHERE user_id = 'USER0001'", String.class);
        assertThat(stored).startsWith("$2").isNotEqualTo("PASSWORD");
        assertThat(encoder.matches("PASSWORD", stored)).isTrue();

        JsonNode wrong = client.post("COSGN00C", "ENTER", Map.of("userId", "USER0001", "password", "PASSWORX"));
        assertThat(wrong.at("/message").asText()).isEqualTo(INVALID);
        assertThat(wrong.at("/fields/userId").asText()).isEqualTo("USER0001");
        assertThat(wrong.at("/focus").asText()).isEqualTo("password");
        assertThat(wrong.has("navigate")).isFalse();

        JsonNode blank = client.post("COSGN00C", "ENTER", Map.of("userId", "", "password", "PASSWORD"));
        assertThat(blank.at("/focus").asText()).isEqualTo("userId");
    }

    @Test
    @Covers({"ERR-COSGN00C-01", "Q-COSGN00C-04"})
    void an_unknown_user_gets_the_generic_message_and_no_session() throws Exception {
        JsonNode r = client.post("COSGN00C", "ENTER", Map.of("userId", "NOSUCHUS", "password", "PASSWORD"));
        assertThat(r.at("/message").asText()).isEqualTo(INVALID);
        assertThat(r.at("/fields/userId").asText()).isEqualTo("NOSUCHUS");
        assertThat(r.has("navigate")).isFalse();
        assertThat(client.showRaw("COMEN01C").getStatus()).isEqualTo(401);
    }

    @Test
    @Covers({"ERR-COSGN00C-02", "TS-COSGN00C-08"})
    void a_failed_user_lookup_shows_unable_to_verify_with_focus_on_user_id() {
        doThrow(new DataAccessResourceFailureException("USRSEC unavailable")).when(users).find(anyString());
        JsonNode r = client.post("COSGN00C", "ENTER", Map.of("userId", "ADMIN001", "password", "PASSWORD"));
        assertThat(r.at("/message").asText()).isEqualTo("Unable to verify the User ...");
        assertThat(r.at("/focus").asText()).isEqualTo("userId");
        assertThat(r.at("/fields/userId").asText()).isEqualTo("ADMIN001");
        assertThat(r.has("navigate")).isFalse();
    }

    @Test
    @Covers({"ERR-COSGN00C-03"})
    void a_turn_with_no_fields_is_handled_as_blank_input() {
        JsonNode r = client.post("COSGN00C", "ENTER", Map.of());
        assertThat(r.at("/message").asText()).isEqualTo("Please enter User ID ...");
        assertThat(r.at("/focus").asText()).isEqualTo("userId");
    }

    @Test
    @Covers({"NAV-COSGN00C-05", "C-SESSION"})
    void after_a_redisplay_the_next_enter_is_handled_by_the_sign_on_screen_again() {
        JsonNode first = client.post("COSGN00C", "ENTER", Map.of("userId", "USER0001", "password", ""));
        assertThat(first.at("/message").asText()).isEqualTo("Please enter Password ...");
        JsonNode second = client.post("COSGN00C", "ENTER", Map.of("userId", "USER0001", "password", "WRONGPWD"));
        assertThat(second.at("/message").asText()).isEqualTo(INVALID);
        JsonNode third = client.post("COSGN00C", "ENTER", Map.of("userId", "USER0001", "password", "PASSWORD"));
        assertThat(third.at("/navigate").asText()).isEqualTo("COMEN01C");
    }

    @Test
    @Covers({"TS-COSGN00C-11", "Q-COSGN00C-02"})
    void a_lower_case_stored_password_signs_on_exactly_as_typed() {
        jdbc.update("INSERT INTO app_user (user_id, first_name, last_name, password_hash, user_type) VALUES (?, ?, ?, ?, 'U')",
            "TEST0001", "TEST", "USER", encoder.encode("abcd1234"));
        assertThat(client.signOn("TEST0001", "abcd1234").at("/navigate").asText()).isEqualTo("COMEN01C");
        assertThat(new ScreenClient(mvc).signOn("TEST0001", "ABCD1234").at("/message").asText()).isEqualTo(INVALID);
    }

    @Test
    @Covers({"TS-COSGN00C-12", "Q-COSGN00C-07"})
    void a_user_type_other_than_a_or_u_cannot_be_stored() {
        assertThatThrownBy(() -> jdbc.update(
            "INSERT INTO app_user (user_id, first_name, last_name, password_hash, user_type) VALUES (?, ?, ?, ?, 'X')",
            "TEST0002", "TEST", "USER", encoder.encode("PASSWORD"))).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(client.signOn("TEST0002").at("/message").asText()).isEqualTo(INVALID);
    }

    @Test
    @Covers({"TS-COSGN00C-13", "Q-COSGN00C-03"})
    void a_partly_typed_password_is_received_as_typed_and_fails() {
        JsonNode r = client.signOn("USER0001", "PASSW");
        assertThat(r.at("/message").asText()).isEqualTo(INVALID);
        assertThat(r.has("navigate")).isFalse();
    }
}
