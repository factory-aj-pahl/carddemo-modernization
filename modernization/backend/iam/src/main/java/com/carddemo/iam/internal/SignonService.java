package com.carddemo.iam.internal;

import com.carddemo.common.audit.AuditLog;
import com.carddemo.common.security.CurrentUser;
import com.carddemo.common.security.Role;
import com.carddemo.iam.security.SecurityProperties;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * COSGN00C authentication with the approved fixes: BCrypt verify (Q-COSGN00C-01), case-insensitive
 * user ID and case-sensitive password (Q-COSGN00C-02), lockout after consecutive failures
 * (Q-COSGN00C-05), constant work for unknown IDs (Q-COSGN00C-04), audit records (C-AUDIT).
 */
@Service
public class SignonService {

    public sealed interface Outcome permits Success, Failure {
    }

    public record Success(CurrentUser user) implements Outcome {
    }

    public record Failure() implements Outcome {
    }

    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final AuditLog audit;
    private final Clock clock;
    private final SecurityProperties props;
    private final String dummyHash;

    public SignonService(UserRepository users, PasswordEncoder encoder, AuditLog audit, Clock clock, SecurityProperties props) {
        this.users = users;
        this.encoder = encoder;
        this.audit = audit;
        this.clock = clock;
        this.props = props;
        this.dummyHash = encoder.encode("dummy-password-for-timing");
    }

    @Transactional
    public Outcome authenticate(String userId, String password, String clientAddr) {
        Optional<UserRecord> found = users.find(userId);
        if (found.isEmpty()) {
            encoder.matches(password, dummyHash);
            audit.record(userId, "SIGNON_FAILURE", userId, List.of(), clientAddr);
            return new Failure();
        }
        UserRecord u = found.get();
        LocalDateTime now = LocalDateTime.now(clock);
        boolean matches = encoder.matches(password, u.passwordHash());
        if (u.lockedUntil() != null && now.isBefore(u.lockedUntil())) {
            audit.record(userId, "SIGNON_FAILURE", userId, List.of("locked"), clientAddr);
            return new Failure();
        }
        int failures = u.lockedUntil() != null ? 0 : u.failedAttempts();
        if (!matches) {
            failures++;
            if (failures >= props.lockoutThreshold()) {
                users.recordFailure(userId, failures, now.plus(props.lockoutDuration()));
                audit.record(userId, "SIGNON_FAILURE", userId, List.of(), clientAddr);
                audit.record(userId, "SIGNON_LOCKOUT", userId, List.of(), clientAddr);
            } else {
                users.recordFailure(userId, failures, null);
                audit.record(userId, "SIGNON_FAILURE", userId, List.of(), clientAddr);
            }
            return new Failure();
        }
        users.resetFailures(userId);
        audit.record(userId, "SIGNON_SUCCESS", userId, List.of(), clientAddr);
        return new Success(new CurrentUser(u.userId(), Role.fromUserType(u.userType()), u.firstName(), u.lastName()));
    }
}
