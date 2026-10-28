package com.carddemo.iam.internal;

import com.carddemo.common.audit.AuditLog;
import com.carddemo.iam.internal.UserAdminRepository.UserDetail;
import com.carddemo.iam.internal.UserForms.Problem;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes of the user maintenance screens: BCrypt hashing (Q-COUSR01C-01), version-checked update
 * (Q-COUSR02C-05), account links (A-3), and audit records without secrets (C-AUDIT).
 */
@Service
public class UserMaintenance {

    public enum UpdateOutcome { UPDATED, NOT_FOUND, CHANGED }

    private final UserAdminRepository repo;
    private final PasswordEncoder encoder;
    private final AuditLog audit;

    public UserMaintenance(UserAdminRepository repo, PasswordEncoder encoder, AuditLog audit) {
        this.repo = repo;
        this.encoder = encoder;
        this.audit = audit;
    }

    public Optional<UserDetail> find(String userId) {
        return repo.find(userId);
    }

    /** True when the typed password equals the stored one (then it is not a change, Q-COUSR02C-03). */
    public boolean passwordMatches(String typed, String hash) {
        return hash != null && encoder.matches(typed, hash);
    }

    /**
     * A-3 link field check, run after the user type is valid: entries must be 11-digit account numbers,
     * only regular users can have links, and every account must exist.
     */
    public LinkCheck checkLinks(String userType, String raw) {
        Optional<Set<Long>> ids = UserForms.accounts(raw);
        if (ids.isEmpty()) {
            return new LinkCheck(new Problem(UserForms.ACCOUNTS_FORMAT, "accounts"), null);
        }
        if (!ids.get().isEmpty() && !"U".equals(userType)) {
            return new LinkCheck(new Problem(UserForms.ACCOUNTS_ADMIN, "accounts"), null);
        }
        if (!repo.missingAccounts(ids.get()).isEmpty()) {
            return new LinkCheck(new Problem(UserForms.ACCOUNT_NOT_FOUND, "accounts"), null);
        }
        return new LinkCheck(null, ids.get());
    }

    /** Result of {@link #checkLinks}: either a problem or the parsed account IDs. */
    public record LinkCheck(Problem problem, Set<Long> accounts) {
    }

    /** Keyed insert of a new user and its links; a duplicate ID raises DuplicateKeyException. */
    @Transactional
    public void add(String actor, String userId, String firstName, String lastName, String password, String userType,
                    Set<Long> accounts) {
        repo.insert(userId, firstName, lastName, encoder.encode(password), userType);
        repo.replaceLinks(userId, accounts);
        List<String> fields = new java.util.ArrayList<>(List.of("firstName", "lastName", "password", "userType"));
        if (!accounts.isEmpty()) {
            fields.add("accounts");
        }
        audit.record(actor, "USER_ADD", userId, fields);
    }

    /**
     * Saves the changed fields if the user's version still matches the one fetched. {@code newPassword}
     * null keeps the stored hash; {@code accounts} null keeps the links.
     */
    @Transactional
    public UpdateOutcome update(String actor, String userId, String version, String firstName, String lastName,
                                String userType, String newPassword, Set<Long> accounts, List<String> changedFields) {
        Optional<UserDetail> current = repo.findForUpdate(userId);
        if (current.isEmpty()) {
            return UpdateOutcome.NOT_FOUND;
        }
        if (!current.get().version().equals(version)) {
            return UpdateOutcome.CHANGED;
        }
        if (repo.update(userId, firstName, lastName, userType, newPassword == null ? null : encoder.encode(newPassword)) == 0) {
            return UpdateOutcome.NOT_FOUND;
        }
        if (accounts != null) {
            repo.replaceLinks(userId, accounts);
        }
        audit.record(actor, "USER_UPDATE", userId, changedFields);
        return UpdateOutcome.UPDATED;
    }

    /** Deletes the user and its links; false when the user no longer exists. */
    @Transactional
    public boolean delete(String actor, String userId) {
        if (repo.delete(userId) == 0) {
            return false;
        }
        audit.record(actor, "USER_DELETE", userId, List.of());
        return true;
    }
}
