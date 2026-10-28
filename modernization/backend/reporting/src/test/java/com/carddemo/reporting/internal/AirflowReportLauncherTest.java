package com.carddemo.reporting.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.carddemo.reporting.internal.ReportLauncher.LaunchFailed;
import com.carddemo.reporting.internal.ReportLauncher.Parameters;
import com.carddemo.test.Covers;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/** The Airflow 3 REST calls behind the CORPT00C launch. */
class AirflowReportLauncherTest {

    static final String BASE = "http://airflow.test";
    static final String RUNS = BASE + "/api/v2/dags/carddemo_transaction_report/dagRuns";

    MockRestServiceServer server;
    AirflowReportLauncher launcher;

    @BeforeEach
    void setUp() {
        RestClient.Builder b = RestClient.builder().baseUrl(BASE);
        server = MockRestServiceServer.bindTo(b).build();
        launcher = new AirflowReportLauncher(b.build(), "admin", "secret");
    }

    private void token(String value) {
        server.expect(requestTo(BASE + "/auth/token")).andExpect(method(HttpMethod.POST))
            .andExpect(content().json("{\"username\":\"admin\",\"password\":\"secret\"}"))
            .andRespond(withStatus(HttpStatus.CREATED).contentType(MediaType.APPLICATION_JSON)
                .body("{\"access_token\":\"" + value + "\",\"token_type\":\"bearer\"}"));
    }

    @Test
    @Covers({"Q-CORPT00C-07"})
    void launch_triggers_the_report_dag_with_the_range_and_requester() throws Exception {
        token("t1");
        server.expect(requestTo(RUNS)).andExpect(method(HttpMethod.POST))
            .andExpect(header("Authorization", "Bearer t1"))
            .andExpect(content().json("""
                {"dag_run_id":"CR00_USER0001_1","logical_date":null,
                 "conf":{"start_date":"2022-06-01","end_date":"2022-06-30","user_id":"USER0001"}}
                """, true))
            .andRespond(withSuccess("{\"dag_run_id\":\"CR00_USER0001_1\",\"state\":\"queued\"}", MediaType.APPLICATION_JSON));
        launcher.launch("CR00_USER0001_1", new Parameters(LocalDate.of(2022, 6, 1), LocalDate.of(2022, 6, 30), "USER0001"));
        server.verify();
    }

    @Test
    void an_expired_token_is_renewed_once_and_reused() throws Exception {
        token("old");
        server.expect(requestTo(RUNS + "/r1")).andExpect(header("Authorization", "Bearer old"))
            .andRespond(withSuccess("{\"state\":\"running\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(RUNS + "/r1")).andExpect(header("Authorization", "Bearer old"))
            .andRespond(withStatus(HttpStatus.UNAUTHORIZED));
        token("new");
        server.expect(requestTo(RUNS + "/r1")).andExpect(header("Authorization", "Bearer new"))
            .andRespond(withSuccess("{\"state\":\"success\"}", MediaType.APPLICATION_JSON));
        assertThat(launcher.state("r1")).contains("running");
        assertThat(launcher.state("r1")).contains("success");
        server.verify();
    }

    @Test
    @Covers({"ERR-CORPT00C-01", "Q-CORPT00C-09"})
    void a_refused_or_unreachable_trigger_is_a_failed_launch() {
        token("t1");
        server.expect(requestTo(RUNS)).andRespond(withStatus(HttpStatus.NOT_FOUND));
        Parameters p = new Parameters(LocalDate.of(2022, 1, 1), LocalDate.of(2022, 12, 31), "ADMIN001");
        assertThatThrownBy(() -> launcher.launch("r2", p)).isInstanceOf(LaunchFailed.class);
        server.verify();
        server.reset();
        server.expect(ExpectedCount.once(), requestTo(RUNS)).andRespond(withServerError());
        assertThatThrownBy(() -> launcher.launch("r3", p)).isInstanceOf(LaunchFailed.class);
    }

    @Test
    void a_failed_sign_in_is_a_failed_launch_and_an_unknown_state_is_empty() {
        server.expect(requestTo(BASE + "/auth/token")).andRespond(withStatus(HttpStatus.UNAUTHORIZED));
        Parameters p = new Parameters(LocalDate.of(2022, 1, 1), LocalDate.of(2022, 12, 31), "ADMIN001");
        assertThatThrownBy(() -> launcher.launch("r4", p)).isInstanceOf(LaunchFailed.class);
        server.verify();
        server.reset();
        token("t2");
        server.expect(requestTo(RUNS + "/gone")).andRespond(withStatus(HttpStatus.NOT_FOUND));
        assertThat(launcher.state("gone")).isEmpty();
    }
}
