package com.carddemo.cycle.internal;

import com.carddemo.common.codec.FixedWidth;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Rejected daily records of a posting run (DALYREJS), kept in the database until the run writes its file. */
@Component
class PostingRejects {

    private final JdbcClient jdbc;
    private final Clock clock;

    PostingRejects(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    void insert(long runId, LocalDate businessDate, String record, int reason, String description) {
        jdbc.sql("""
                INSERT INTO posting_reject (run_id, business_date, record_text, reason_code, reason_desc, created_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """)
            .params(runId, businessDate, record, reason, description, LocalDateTime.now(clock)).update();
    }

    /** BR-CBTRN02C-09: the daily record, the reason as 4 digits, and the description padded to 76 bytes. */
    List<String> records(long runId) {
        return jdbc.sql("SELECT record_text, reason_code, reason_desc FROM posting_reject WHERE run_id = ? ORDER BY reject_id")
            .param(runId)
            .query((rs, i) -> FixedWidth.x(rs.getString(1), DailyRecord.LENGTH) + FixedWidth.n(rs.getInt(2), 4)
                + FixedWidth.x(rs.getString(3), 76))
            .list();
    }
}
