package com.carddemo.iam;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;

import com.carddemo.iam.internal.UserAdminRepository;
import com.carddemo.test.Covers;
import com.carddemo.test.TestApplication;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/** Data-access failures of the user maintenance screens (the COBOL "other RESP" branches). */
@SpringBootTest(classes = TestApplication.class)
@AutoConfigureMockMvc
class UserScreenFailureTest extends UserScreenTestSupport {

    static final DataAccessResourceFailureException DOWN = new DataAccessResourceFailureException("USRSEC unavailable");

    @MockitoSpyBean
    UserAdminRepository repo;

    @Test
    @Covers({"ERR-COUSR00C-02", "TS-COUSR00C-15"})
    void the_list_reports_a_lookup_failure_on_first_entry() {
        doThrow(DOWN).when(repo).from(any(), anyInt());
        JsonNode r = admin.show("COUSR00C");
        assertThat(r.at("/message").asText()).isEqualTo("Unable to lookup User...");
        assertThat(r.at("/focus").asText()).isEqualTo("searchUserId");
        assertThat(userIds(r)).containsOnly("");
    }

    @Test
    @Covers({"ERR-COUSR00C-04", "ERR-COUSR00C-06"})
    void paging_reports_a_lookup_failure() {
        twentyFiveUsers();
        admin.show("COUSR00C");
        admin.post("COUSR00C", "PF8", Map.of());
        doThrow(DOWN).when(repo).before(anyString(), anyInt());
        assertThat(admin.post("COUSR00C", "PF7", Map.of()).at("/message").asText()).isEqualTo("Unable to lookup User...");
        doThrow(DOWN).when(repo).after(anyString(), anyInt());
        admin.show("COUSR00C");
        JsonNode p2 = admin.post("COUSR00C", "PF8", Map.of());
        assertThat(p2.at("/message").asText()).isEqualTo("Unable to lookup User...");
    }

    @Test
    @Covers({"ERR-COUSR01C-02", "TS-COUSR01C-14"})
    void add_reports_a_write_failure() {
        doThrow(DOWN).when(repo).insert(anyString(), anyString(), anyString(), anyString(), anyString());
        JsonNode r = admin.post("COUSR01C", "ENTER", Map.of("firstName", "TEST", "lastName", "USER", "userId", "TEST0001",
            "password", "PASSWORD", "userType", "U"));
        assertThat(r.at("/message").asText()).isEqualTo("Unable to Add User...");
        assertThat(r.at("/focus").asText()).isEqualTo("firstName");
        assertThat(exists("TEST0001")).isFalse();
    }

    @Test
    @Covers({"ERR-COUSR02C-02", "ERR-COUSR02C-05"})
    void update_reports_read_and_write_failures() {
        admin.show("COUSR02C");
        JsonNode ok = admin.post("COUSR02C", "ENTER", Map.of("userId", "USER0001"));
        doThrow(DOWN).when(repo).update(anyString(), anyString(), anyString(), anyString(), any());
        JsonNode save = admin.post("COUSR02C", "PF5", Map.of("userId", "USER0001", "firstName", "BROKEN",
            "lastName", ok.at("/fields/lastName").asText(), "userType", "U", "accounts", ok.at("/fields/accounts").asText()));
        assertThat(save.at("/message").asText()).isEqualTo("Unable to Update User...");
        assertThat(save.at("/focus").asText()).isEqualTo("firstName");
        assertThat(column("USER0001", "first_name")).isNotEqualTo("BROKEN");
        doThrow(DOWN).when(repo).find(anyString());
        JsonNode fetch = admin.post("COUSR02C", "ENTER", Map.of("userId", "USER0002"));
        assertThat(fetch.at("/message").asText()).isEqualTo("Unable to lookup User...");
    }

    @Test
    @Covers({"ERR-COUSR03C-02", "ERR-COUSR03C-05", "Q-COUSR03C-06"})
    void delete_reports_read_and_delete_failures_with_focus_on_user_id() {
        admin.show("COUSR03C");
        admin.post("COUSR03C", "ENTER", Map.of("userId", "USER0001"));
        doThrow(DOWN).when(repo).delete(anyString());
        JsonNode del = admin.post("COUSR03C", "PF5", Map.of("userId", "USER0001"));
        assertThat(del.at("/message").asText()).isEqualTo("Unable to Update User...");
        assertThat(del.at("/focus").asText()).isEqualTo("userId");
        assertThat(exists("USER0001")).isTrue();
        doThrow(DOWN).when(repo).find(anyString());
        JsonNode fetch = admin.post("COUSR03C", "ENTER", Map.of("userId", "USER0002"));
        assertThat(fetch.at("/message").asText()).isEqualTo("Unable to lookup User...");
        assertThat(fetch.at("/focus").asText()).isEqualTo("userId");
    }
}
