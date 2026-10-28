package com.carddemo.transaction.web;

import com.carddemo.common.mask.Masking;

/** Field handling shared by the transaction screens. */
final class Input {

    private Input() {
    }

    /** The field as the BMS map returns it: at most {@code len} characters, never null. */
    static String cut(String s, int len) {
        if (s == null) {
            return "";
        }
        return s.length() > len ? s.substring(0, len) : s;
    }

    /** COBOL NUMERIC on a field of {@code len} positions: exactly {@code len} digits (C-KEYLEN). */
    static boolean digits(String s, int len) {
        return s != null && s.length() == len && s.chars().allMatch(c -> c >= '0' && c <= '9');
    }

    /** First {@code len} characters of a stored value, or empty. */
    static String part(String s, int len) {
        return cut(s == null ? "" : s, len);
    }

    /** C-MASK for a value shown in a card field: a 16-digit number is masked, anything else is echoed. */
    static String cardShown(String value) {
        if (value == null) {
            return "";
        }
        return digits(value, 16) ? Masking.card(value) : value;
    }
}
