package com.carddemo.refdata.internal;

/**
 * Field edits shared by COTRTUPC, COTRTLIC (row description) and COBTUPDT (Q-COBTUPDT-05): the type code is one
 * or two digits, 01-99 (Q-COTRTUPC-14); the description is required and holds only letters, digits and spaces.
 */
public final class TypeEdits {

    public static final String TYPE_REQUIRED = "Tran Type code must be supplied.";
    public static final String TYPE_NUMERIC = "Tran Type code must be numeric.";
    public static final String TYPE_NOT_ZERO = "Tran Type code must not be zero.";
    public static final String DESC_REQUIRED = "Transaction Desc must be supplied.";
    public static final String DESC_ALPHANUM = "Transaction Desc can have numbers or alphabets only.";

    public enum Check { BLANK, INVALID, ZERO, OK }

    /** @param code the normalized two-digit code when the check is OK, else null */
    public record TypeResult(Check check, String code) {
    }

    private TypeEdits() {
    }

    public static TypeResult typeCode(String raw) {
        String t = trim(raw);
        if (t.isEmpty() || t.equals("*")) {
            return new TypeResult(Check.BLANK, null);
        }
        if (!t.matches("[0-9]{1,2}")) {
            return new TypeResult(Check.INVALID, null);
        }
        int v = Integer.parseInt(t);
        return v == 0 ? new TypeResult(Check.ZERO, null) : new TypeResult(Check.OK, String.format("%02d", v));
    }

    public static String typeMessage(Check c) {
        return switch (c) {
            case BLANK -> TYPE_REQUIRED;
            case INVALID -> TYPE_NUMERIC;
            case ZERO -> TYPE_NOT_ZERO;
            case OK -> null;
        };
    }

    /** 1240-EDIT-ALPHANUM-REQD / 1230-EDIT-ALPHANUM-REQD. */
    public static Check description(String d) {
        if (d == null || trim(d).isEmpty()) {
            return Check.BLANK;
        }
        return d.chars().allMatch(c -> c == ' ' || (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9'))
            ? Check.OK : Check.INVALID;
    }

    public static String descriptionMessage(Check c) {
        return switch (c) {
            case BLANK -> DESC_REQUIRED;
            case INVALID -> DESC_ALPHANUM;
            default -> null;
        };
    }

    /** COBOL FUNCTION TRIM: leading and trailing spaces only. */
    public static String trim(String s) {
        if (s == null) {
            return "";
        }
        int b = 0;
        int e = s.length();
        while (b < e && s.charAt(b) == ' ') {
            b++;
        }
        while (e > b && s.charAt(e - 1) == ' ') {
            e--;
        }
        return s.substring(b, e);
    }

    public static String rtrim(String s) {
        if (s == null) {
            return "";
        }
        int e = s.length();
        while (e > 0 && s.charAt(e - 1) == ' ') {
            e--;
        }
        return s.substring(0, e);
    }
}
