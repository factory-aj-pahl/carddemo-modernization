package com.carddemo.iam.internal;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** Field handling and verbatim messages shared by the user maintenance screens (COUSR00C-03C). */
public final class UserForms {

    public static final String FIRST_EMPTY = "First Name can NOT be empty...";
    public static final String LAST_EMPTY = "Last Name can NOT be empty...";
    public static final String ID_EMPTY = "User ID can NOT be empty...";
    public static final String PASSWORD_EMPTY = "Password can NOT be empty...";
    public static final String TYPE_EMPTY = "User Type can NOT be empty...";
    public static final String ID_NOT_FOUND = "User ID NOT found...";
    public static final String LOOKUP_FAILED = "Unable to lookup User...";
    public static final String UPDATE_FAILED = "Unable to Update User...";
    /** Q-COUSR01C-06 (NEW). */
    public static final String ID_FORMAT = "User ID must be letters and digits only...";
    /** Q-COUSR01C-03, Q-COUSR02C-04 (NEW). */
    public static final String TYPE_VALUE = "User Type must be A or U...";
    /** Reused from COACTUPC/COCRDUPC (Q-COUSR02C-05). */
    public static final String CHANGED_BY_OTHER = "Record changed by some one else. Please review";
    /** A-3 (NEW): link field entries are 11-digit account numbers (C-KEYLEN). */
    public static final String ACCOUNTS_FORMAT = "Linked accounts must be 11 digit numbers...";
    /** A-3 (NEW): links scope regular users only; admins are unrestricted. */
    public static final String ACCOUNTS_ADMIN = "Only regular users can have linked accounts...";
    /** A-3: reused from COBIL00C/COTRN02C. */
    public static final String ACCOUNT_NOT_FOUND = "Account ID NOT found...";

    public static final int NAME_LEN = 20;
    public static final int ID_LEN = 8;
    public static final int PASSWORD_LEN = 8;

    private static final Pattern ID = Pattern.compile("[A-Z0-9]{1,8}");
    private static final Pattern ACCOUNT = Pattern.compile("\\d{11}");
    private static final Pattern SEPARATORS = Pattern.compile("[,;\\s]+");

    /** A validation failure: the message and the field that gets focus. */
    public record Problem(String message, String focus) {
    }

    private UserForms() {
    }

    /** Spaces or LOW-VALUES only (the COBOL blank test). */
    public static boolean blank(String s) {
        return s == null || s.chars().allMatch(c -> c == ' ' || c == 0);
    }

    /** Input as the BMS field holds it: cut to the field length, trailing padding removed. */
    public static String field(String s, int len) {
        if (s == null) {
            return "";
        }
        String v = s.length() > len ? s.substring(0, len) : s;
        return v.stripTrailing();
    }

    /** Q-COSGN00C-02: user IDs are case-insensitive and kept upper case. */
    public static String userId(String raw) {
        return field(raw, ID_LEN).toUpperCase();
    }

    public static boolean validUserId(String id) {
        return ID.matcher(id).matches();
    }

    /** User type upper-cased (Q-COUSR01C-03); validity checked separately. */
    public static String userType(String raw) {
        return field(raw, 1).toUpperCase();
    }

    public static boolean validType(String type) {
        return "A".equals(type) || "U".equals(type);
    }

    /** Password exactly as typed (case-sensitive, Q-COSGN00C-02), cut to the field length. */
    public static String password(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.length() > PASSWORD_LEN ? raw.substring(0, PASSWORD_LEN) : raw;
    }

    /** Link field: 11-digit account numbers separated by commas or spaces; empty when any entry is malformed. */
    public static Optional<Set<Long>> accounts(String raw) {
        if (blank(raw)) {
            return Optional.of(Set.of());
        }
        Set<Long> ids = new TreeSet<>();
        for (String token : SEPARATORS.split(raw.strip())) {
            if (!ACCOUNT.matcher(token).matches()) {
                return Optional.empty();
            }
            ids.add(Long.parseLong(token));
        }
        return Optional.of(Collections.unmodifiableSet(new LinkedHashSet<>(ids)));
    }

    public static String formatAccounts(Set<Long> ids) {
        return ids.stream().sorted().map(id -> String.format("%011d", id)).collect(Collectors.joining(", "));
    }

    public static String formatAccount(long id) {
        return String.format("%011d", id);
    }
}
