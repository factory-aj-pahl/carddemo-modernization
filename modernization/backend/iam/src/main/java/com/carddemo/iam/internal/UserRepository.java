package com.carddemo.iam.internal;

import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class UserRepository {

    private final JdbcClient jdbc;

    public UserRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<UserRecord> find(String userId) {
        return jdbc.sql("""
                SELECT user_id, first_name, last_name, password_hash, user_type, failed_attempts, locked_until
                FROM app_user WHERE user_id = ?""")
            .param(userId)
            .query((rs, n) -> new UserRecord(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                rs.getString(5), rs.getInt(6),
                rs.getTimestamp(7) == null ? null : rs.getTimestamp(7).toLocalDateTime()))
            .optional();
    }

    public void recordFailure(String userId, int failedAttempts, LocalDateTime lockedUntil) {
        jdbc.sql("UPDATE app_user SET failed_attempts = ?, locked_until = ? WHERE user_id = ?")
            .params(failedAttempts, lockedUntil, userId).update();
    }

    public void resetFailures(String userId) {
        jdbc.sql("UPDATE app_user SET failed_attempts = 0, locked_until = NULL WHERE user_id = ?")
            .param(userId).update();
    }
}
