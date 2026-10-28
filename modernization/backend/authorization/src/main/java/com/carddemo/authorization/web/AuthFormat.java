package com.carddemo.authorization.web;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Screen renderings of the authorization screens. */
final class AuthFormat {

    private AuthFormat() {
    }

    /**
     * An edit picture {@code -z...z9.99} of {@code width} characters: a fixed leading sign position ('-' or space),
     * zero-suppressed integer digits, two decimals. Larger values get more digits instead of losing the high-order
     * ones (Q-COPAUS0C-06).
     */
    static String amount(BigDecimal value, int width) {
        if (value == null) {
            return "";
        }
        BigDecimal v = value.setScale(2, RoundingMode.DOWN);
        String body = v.abs().toPlainString();
        int pad = width - 1 - body.length();
        return (v.signum() < 0 ? "-" : " ") + " ".repeat(Math.max(0, pad)) + body;
    }

    /** A {@code 9(03)} count: every digit, no leading zeros, zero as {@code 0} (Q-COPAUS0C-06). */
    static String count(int n) {
        return Integer.toString(n);
    }

    /** YYMMDD as MM/DD/YY. */
    static String date(String yymmdd) {
        String s = fixed(yymmdd, 6);
        return s.substring(2, 4) + "/" + s.substring(4, 6) + "/" + s.substring(0, 2);
    }

    /** HHMMSS as HH:MM:SS. */
    static String time(String hhmmss) {
        String s = fixed(hhmmss, 6);
        return s.substring(0, 2) + ":" + s.substring(2, 4) + ":" + s.substring(4, 6);
    }

    /** MMYY as MM/YY. */
    static String expiry(String mmyy) {
        String s = fixed(mmyy, 4);
        return s.substring(0, 2) + "/" + s.substring(2, 4);
    }

    /** The value cut or padded with spaces to {@code n} characters, like a MOVE to {@code X(n)}. */
    static String fixed(String s, int n) {
        String v = s == null ? "" : s;
        return v.length() >= n ? v.substring(0, n) : v + " ".repeat(n - v.length());
    }

    /** The value cut to the BMS field length (trailing spaces dropped). */
    static String field(String s, int n) {
        return fixed(s, n).stripTrailing();
    }

    /** STRING ... DELIMITED BY {@code delimiter}: the text before the first occurrence (all of it when absent). */
    static String upTo(String s, String delimiter) {
        if (s == null) {
            return "";
        }
        int i = s.indexOf(delimiter);
        return i < 0 ? s : s.substring(0, i);
    }
}
