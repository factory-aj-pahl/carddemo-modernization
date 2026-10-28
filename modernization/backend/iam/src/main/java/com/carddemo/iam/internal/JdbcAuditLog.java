package com.carddemo.iam.internal;

import com.carddemo.common.audit.AuditLog;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** C-AUDIT records in audit_log. */
@Component
public class JdbcAuditLog implements AuditLog {

    private final JdbcClient jdbc;
    private final Clock clock;

    public JdbcAuditLog(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Override
    public void record(String actor, String action, String target, List<String> changedFields, String clientAddr) {
        jdbc.sql("INSERT INTO audit_log (recorded_at, actor, action, target, fields, client_addr) VALUES (?, ?, ?, ?, ?, ?)")
            .params(LocalDateTime.now(clock), actor, action, target,
                changedFields == null || changedFields.isEmpty() ? null : String.join(",", changedFields), clientAddr)
            .update();
    }
}
