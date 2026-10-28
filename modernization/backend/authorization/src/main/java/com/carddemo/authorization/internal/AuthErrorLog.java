package com.carddemo.authorization.internal;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * COPAUA0C 9500-LOG-ERROR (BR-COPAUA0C-19): one CCPAUERY record per error, kept in {@code auth_error_log} and
 * written as a structured application log line. The record commits on its own, so it survives the rollback of
 * the message's transaction (Q-COPAUA0C-07). A failure to log is ignored (ERR-COPAUA0C-12).
 */
@Component
public class AuthErrorLog {

    public static final String APPLICATION = "CP00";
    public static final String PROGRAM = "COPAUA0C";
    private static final Logger LOG = LoggerFactory.getLogger(AuthErrorLog.class);
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyMMdd");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HHmmss");

    /** ERR-LEVEL. */
    public enum Level {
        LOG("L"), INFO("I"), WARNING("W"), CRITICAL("C");

        final String code;

        Level(String code) {
            this.code = code;
        }
    }

    /** ERR-SUBSYSTEM. */
    public enum Subsystem {
        APP("A"), CICS("C"), IMS("I"), DB2("D"), MQ("M"), FILE("F");

        final String code;

        Subsystem(String code) {
            this.code = code;
        }
    }

    /** One CCPAUERY record (ERR-DATE ... ERR-EVENT-KEY). */
    public record Entry(String date, String time, String location, Level level, Subsystem subsystem, String code1,
            String code2, String message, String eventKey) {
    }

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final TransactionTemplate ownTransaction;

    AuthErrorLog(JdbcTemplate jdbc, Clock clock, PlatformTransactionManager tm) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.ownTransaction = new TransactionTemplate(tm);
        this.ownTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public Entry log(String location, Level level, Subsystem subsystem, String code1, String code2, String message,
            String eventKey) {
        LocalDateTime now = LocalDateTime.now(clock);
        Entry e = new Entry(now.format(DATE), now.format(TIME), location, level, subsystem, cut(code1, 9), cut(code2, 9),
            cut(message, 50), cut(eventKey, 20));
        String line = "application=" + APPLICATION + " program=" + PROGRAM + " location=" + e.location() + " level="
            + level.code + " subsystem=" + subsystem.code + " code1=" + e.code1() + " code2=" + e.code2() + " message=\""
            + e.message() + "\" eventKey=" + e.eventKey();
        if (level == Level.CRITICAL) {
            LOG.error(line);
        } else {
            LOG.warn(line);
        }
        try {
            ownTransaction.executeWithoutResult(s -> jdbc.update("INSERT INTO auth_error_log (logged_at, err_date, err_time, "
                + "application, program, location, err_level, subsystem, code1, code2, message, event_key) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", java.sql.Timestamp.valueOf(now), e.date(), e.time(), APPLICATION,
                PROGRAM, e.location(), level.code, subsystem.code, e.code1(), e.code2(), e.message(), e.eventKey()));
        } catch (RuntimeException ex) {
            LOG.warn("error log record not stored: {}", ex.toString());
        }
        return e;
    }

    private static String cut(String s, int len) {
        String v = s == null ? "" : s;
        return v.length() > len ? v.substring(0, len) : v;
    }
}
