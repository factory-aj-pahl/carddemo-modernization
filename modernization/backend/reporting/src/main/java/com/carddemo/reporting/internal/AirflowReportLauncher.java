package com.carddemo.reporting.internal;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Airflow 3 REST API client: a JWT from {@code POST /auth/token}, then
 * {@code POST /api/v2/dags/carddemo_transaction_report/dagRuns} with conf {@code start_date}, {@code end_date},
 * {@code user_id}. The run ID is chosen by the caller so the run can be recorded before it starts.
 */
@Component
public class AirflowReportLauncher implements ReportLauncher {

    public static final String DAG_ID = "carddemo_transaction_report";

    private static final Logger log = LoggerFactory.getLogger(AirflowReportLauncher.class);

    private final RestClient http;
    private final String username;
    private final String password;
    private volatile String token;

    @Autowired
    public AirflowReportLauncher(RestClient.Builder builder,
                                 @Value("${carddemo.airflow.base-url:http://127.0.0.1:8080}") String baseUrl,
                                 @Value("${carddemo.airflow.username:admin}") String username,
                                 @Value("${carddemo.airflow.password:}") String password,
                                 @Value("${carddemo.airflow.timeout-ms:5000}") int timeoutMs) {
        this(builder.baseUrl(baseUrl).requestFactory(timeouts(timeoutMs)).build(), username, password);
    }

    AirflowReportLauncher(RestClient http, String username, String password) {
        this.http = http;
        this.username = username;
        this.password = password;
    }

    private static SimpleClientHttpRequestFactory timeouts(int timeoutMs) {
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout(timeoutMs);
        f.setReadTimeout(timeoutMs);
        return f;
    }

    @Override
    public void launch(String runId, Parameters p) throws LaunchFailed {
        Map<String, Object> conf = new LinkedHashMap<>();
        conf.put("start_date", p.startDate().toString());
        conf.put("end_date", p.endDate().toString());
        conf.put("user_id", p.userId());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("dag_run_id", runId);
        // Airflow 3.0 requires the key; null means a manual run with no data interval.
        body.put("logical_date", null);
        body.put("conf", conf);
        try {
            authorized(() -> http.post().uri("/api/v2/dags/{dag}/dagRuns", DAG_ID).headers(this::bearer)
                .contentType(MediaType.APPLICATION_JSON).body(body).retrieve().toBodilessEntity());
        } catch (RestClientException e) {
            log.warn("Report run {} was not started: {}", runId, e.getMessage());
            throw new LaunchFailed("Report run was not started", e);
        }
    }

    @Override
    public Optional<String> state(String runId) {
        try {
            Map<?, ?> run = authorized(() -> http.get().uri("/api/v2/dags/{dag}/dagRuns/{run}", DAG_ID, runId)
                .headers(this::bearer).retrieve().body(Map.class));
            return run != null && run.get("state") instanceof String s ? Optional.of(s) : Optional.empty();
        } catch (RestClientException e) {
            log.debug("State of report run {} unavailable: {}", runId, e.getMessage());
            return Optional.empty();
        }
    }

    /** Runs a call with the cached token; on 401 gets a new token once and repeats the call. */
    private <T> T authorized(java.util.function.Supplier<T> call) {
        if (token == null) {
            token = newToken();
        }
        try {
            return call.get();
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode() != HttpStatus.UNAUTHORIZED) {
                throw e;
            }
            token = newToken();
            return call.get();
        }
    }

    private void bearer(HttpHeaders h) {
        h.setBearerAuth(token);
    }

    private String newToken() {
        Map<?, ?> res = http.post().uri("/auth/token").contentType(MediaType.APPLICATION_JSON)
            .body(Map.of("username", username, "password", password)).retrieve().body(Map.class);
        if (res == null || !(res.get("access_token") instanceof String t) || t.isEmpty()) {
            throw new RestClientException("Airflow returned no access token");
        }
        return t;
    }
}
