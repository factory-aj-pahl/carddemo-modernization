package com.carddemo.iam.internal;

import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * USRSEC maintenance over app_user and user_account: keyset browse in user-ID order (the BIN2
 * collation sorts like the VSAM key), plain reads with the row version, and version-checked writes.
 */
@Repository
public class UserAdminRepository {

    /** One list row (BR-COUSR00C-04): never carries the password hash. */
    public record UserRow(String userId, String firstName, String lastName, String userType) implements Serializable {
    }

    /**
     * A fetched user with its linked accounts (A-3) and the version of the maintained values
     * (Q-COUSR02C-05). The version is a fingerprint of names, type, password hash, and links rather than
     * the rowversion column, because sign-on bookkeeping (failed attempts, lockout) rewrites the row
     * without changing anything the update screen shows.
     */
    public record UserDetail(String userId, String firstName, String lastName, String userType, String passwordHash,
                             String version, Set<Long> accounts) {
    }

    private static final String COLS = "user_id, first_name, last_name, user_type";

    private final JdbcClient jdbc;

    public UserAdminRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Up to {@code limit} users with ID greater than or equal to {@code start} (all users when null), ascending. */
    public List<UserRow> from(String start, int limit) {
        if (start == null || start.isEmpty()) {
            return jdbc.sql("SELECT TOP (?) " + COLS + " FROM app_user ORDER BY user_id").param(limit).query(this::row).list();
        }
        return jdbc.sql("SELECT TOP (?) " + COLS + " FROM app_user WHERE user_id >= ? ORDER BY user_id")
            .params(limit, start).query(this::row).list();
    }

    /** Up to {@code limit} users with ID greater than {@code key}, ascending (forward page, exclusive bound). */
    public List<UserRow> after(String key, int limit) {
        return jdbc.sql("SELECT TOP (?) " + COLS + " FROM app_user WHERE user_id > ? ORDER BY user_id")
            .params(limit, key).query(this::row).list();
    }

    /** Up to {@code limit} users with ID less than {@code key}, nearest first (backward page, exclusive bound). */
    public List<UserRow> before(String key, int limit) {
        return jdbc.sql("SELECT TOP (?) " + COLS + " FROM app_user WHERE user_id < ? ORDER BY user_id DESC")
            .params(limit, key).query(this::row).list();
    }

    public boolean existsAfter(String key) {
        return jdbc.sql("SELECT COUNT(*) FROM app_user WHERE user_id > ?").param(key).query(Integer.class).single() > 0;
    }

    public Optional<UserDetail> find(String userId) {
        return read(userId, "");
    }

    /** Read inside the caller's transaction, holding an update lock on the row until it ends. */
    public Optional<UserDetail> findForUpdate(String userId) {
        return read(userId, " WITH (UPDLOCK, ROWLOCK)");
    }

    private Optional<UserDetail> read(String userId, String hint) {
        return jdbc.sql("SELECT user_id, first_name, last_name, user_type, password_hash FROM app_user" + hint
                + " WHERE user_id = ?")
            .param(userId)
            .query((rs, n) -> new String[] {rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5)})
            .optional()
            .map(r -> {
                Set<Long> accounts = links(r[0]);
                return new UserDetail(r[0], r[1], r[2], r[3], r[4], fingerprint(r, accounts), accounts);
            });
    }

    private static String fingerprint(String[] row, Set<Long> accounts) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            for (int i = 1; i < row.length; i++) {
                md.update(String.valueOf(row[i]).getBytes(StandardCharsets.UTF_8));
                md.update((byte) 0);
            }
            md.update(accounts.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public Set<Long> links(String userId) {
        return Collections.unmodifiableSet(new LinkedHashSet<>(
            jdbc.sql("SELECT account_id FROM user_account WHERE user_id = ? ORDER BY account_id").param(userId)
                .query(Long.class).list()));
    }

    /**
     * A-3: account existence for the link field. The account table belongs to the account context; IAM
     * reads only key existence because a Maven dependency on that context would form a cycle (its tests
     * use IAM for sign-on and scoping).
     */
    public List<Long> missingAccounts(Collection<Long> accountIds) {
        List<Long> missing = new ArrayList<>();
        for (Long id : accountIds) {
            boolean found = jdbc.sql("SELECT COUNT(*) FROM account WHERE account_id = ?").param(id)
                .query(Integer.class).single() > 0;
            if (!found) {
                missing.add(id);
            }
        }
        return missing;
    }

    /** Keyed insert; a duplicate user ID raises {@link org.springframework.dao.DuplicateKeyException}. */
    public void insert(String userId, String firstName, String lastName, String passwordHash, String userType) {
        jdbc.sql("""
                INSERT INTO app_user (user_id, first_name, last_name, password_hash, user_type, failed_attempts)
                VALUES (?, ?, ?, ?, ?, 0)""")
            .params(userId, firstName, lastName, passwordHash, userType).update();
    }

    /** Update of the maintained columns; {@code passwordHash} null keeps the stored hash. Returns the rows changed. */
    public int update(String userId, String firstName, String lastName, String userType, String passwordHash) {
        return jdbc.sql("""
                UPDATE app_user SET first_name = ?, last_name = ?, user_type = ?,
                    password_hash = COALESCE(?, password_hash)
                WHERE user_id = ?""")
            .params(firstName, lastName, userType, passwordHash, userId).update();
    }

    public void replaceLinks(String userId, Set<Long> accounts) {
        jdbc.sql("DELETE FROM user_account WHERE user_id = ?").param(userId).update();
        for (Long a : accounts) {
            jdbc.sql("INSERT INTO user_account (user_id, account_id) VALUES (?, ?)").params(userId, a).update();
        }
    }

    /** Deletes the user (links cascade). Returns the rows deleted. */
    public int delete(String userId) {
        return jdbc.sql("DELETE FROM app_user WHERE user_id = ?").param(userId).update();
    }

    private UserRow row(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        return new UserRow(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4));
    }
}
