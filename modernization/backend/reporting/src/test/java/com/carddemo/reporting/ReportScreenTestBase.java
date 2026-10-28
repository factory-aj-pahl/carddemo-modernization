package com.carddemo.reporting;

import com.carddemo.reporting.internal.ReportLauncher;
import com.carddemo.test.SampleData;
import com.carddemo.test.ScreenClient;
import com.carddemo.test.TestApplication;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/** CORPT00C against the sample users, a settable clock (default 2022-06-15), and a recording launcher. */
@SpringBootTest(classes = {TestApplication.class, ReportScreenTestBase.Stubs.class})
@AutoConfigureMockMvc
abstract class ReportScreenTestBase {

    static final String ADMIN = "ADMIN001";
    static final String USER = "USER0001";
    static final Path OUTBOX;

    static {
        try {
            OUTBOX = Files.createTempDirectory("carddemo-reports-");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @DynamicPropertySource
    static void outbox(DynamicPropertyRegistry r) {
        r.add("carddemo.reports.outbox", OUTBOX::toString);
    }

    /** A clock tests move between sign-ons (the idle timeout uses it too). */
    static final class SettableClock extends Clock {
        private volatile Instant now;
        private final ZoneId zone = ZoneId.systemDefault();

        void set(LocalDateTime t) {
            now = t.atZone(zone).toInstant();
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId z) {
            return Clock.fixed(now, z);
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    /** Records launches instead of calling Airflow. */
    static final class StubLauncher implements ReportLauncher {
        final List<Map<String, String>> launches = new ArrayList<>();
        final Map<String, String> states = new HashMap<>();
        volatile boolean fail;

        @Override
        public synchronized void launch(String runId, Parameters p) throws LaunchFailed {
            if (fail) {
                throw new LaunchFailed("stub failure", null);
            }
            Map<String, String> l = new LinkedHashMap<>();
            l.put("runId", runId);
            l.put("start_date", p.startDate().toString());
            l.put("end_date", p.endDate().toString());
            l.put("user_id", p.userId());
            launches.add(l);
        }

        @Override
        public synchronized Optional<String> state(String runId) {
            return Optional.ofNullable(states.get(runId));
        }

        synchronized void reset() {
            launches.clear();
            states.clear();
            fail = false;
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Stubs {
        @Bean
        @Primary
        SettableClock settableClock() {
            SettableClock c = new SettableClock();
            c.set(LocalDateTime.of(2022, 6, 15, 10, 30));
            return c;
        }

        @Bean
        @Primary
        StubLauncher stubLauncher() {
            return new StubLauncher();
        }
    }

    @Autowired
    MockMvc mvc;
    @Autowired
    SampleData sample;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    SettableClock clock;
    @Autowired
    StubLauncher launcher;

    ScreenClient client;

    @BeforeEach
    void reset() {
        sample.load();
        jdbc.update("DELETE FROM report_run");
        launcher.reset();
        clock.set(LocalDateTime.of(2022, 6, 15, 10, 30));
        client = new ScreenClient(mvc);
    }

    static Map<String, String> form(String type) {
        Map<String, String> f = new LinkedHashMap<>();
        f.put("reportType", type);
        f.put("startMonth", "");
        f.put("startDay", "");
        f.put("startYear", "");
        f.put("endMonth", "");
        f.put("endDay", "");
        f.put("endYear", "");
        f.put("confirm", "");
        return f;
    }

    static Map<String, String> custom(String sm, String sd, String sy, String em, String ed, String ey, String confirm) {
        Map<String, String> f = form("custom");
        f.put("startMonth", sm);
        f.put("startDay", sd);
        f.put("startYear", sy);
        f.put("endMonth", em);
        f.put("endDay", ed);
        f.put("endYear", ey);
        f.put("confirm", confirm);
        return f;
    }

    static Map<String, String> with(Map<String, String> f, String key, String value) {
        Map<String, String> c = new LinkedHashMap<>(f);
        c.put(key, value);
        return c;
    }

    JsonNode enter(Map<String, String> f) {
        return client.post("CORPT00C", "ENTER", f);
    }

    int runCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM report_run", Integer.class);
    }

    void insertRun(String runId, String userId, String state) {
        jdbc.update("""
            INSERT INTO report_run (dag_run_id, report_type, start_date, end_date, user_id, requested_at, state)
            VALUES (?, 'Monthly', '2022-06-01', '2022-06-30', ?, '2022-06-15T09:00:00', ?)
            """, runId, userId, state);
    }

    Path writeReport(String runId, String content) {
        try {
            Path dir = Files.createDirectories(OUTBOX.resolve("reports").resolve(runId));
            return Files.writeString(dir.resolve("TRANREPT.txt"), content);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
