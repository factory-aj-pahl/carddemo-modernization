package com.carddemo.app;

import com.carddemo.reporting.internal.ReportLauncher;
import com.carddemo.test.SampleData;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;
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

/**
 * The assembled API with a settable application clock (sign-on lockout and idle timeout use it), a
 * private report outbox, and a report launcher that never calls Airflow. Subclasses share one context.
 */
@SpringBootTest(classes = {CardDemoApplication.class, SecurityTestBase.Stubs.class})
@AutoConfigureMockMvc
abstract class SecurityTestBase {

    static final LocalDateTime T0 = LocalDateTime.of(2022, 6, 15, 10, 30);
    static final Path OUTBOX;

    static {
        try {
            OUTBOX = Files.createTempDirectory("carddemo-security-");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @DynamicPropertySource
    static void outbox(DynamicPropertyRegistry r) {
        r.add("carddemo.reports.outbox", OUTBOX::toString);
    }

    static final class SettableClock extends Clock {
        private volatile Instant now;
        private final ZoneId zone = ZoneId.systemDefault();

        void set(LocalDateTime t) {
            now = t.atZone(zone).toInstant();
        }

        void advance(Duration d) {
            now = now.plus(d);
        }

        LocalDateTime now() {
            return LocalDateTime.ofInstant(now, zone);
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

    /** Accepts every launch and knows no scheduler state, so no request reaches Airflow. */
    static final class NoAirflow implements ReportLauncher {
        @Override
        public void launch(String runId, Parameters parameters) {
        }

        @Override
        public Optional<String> state(String runId) {
            return Optional.empty();
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Stubs {
        @Bean
        @Primary
        SettableClock settableClock() {
            SettableClock c = new SettableClock();
            c.set(T0);
            return c;
        }

        @Bean
        @Primary
        NoAirflow noAirflow() {
            return new NoAirflow();
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
}
