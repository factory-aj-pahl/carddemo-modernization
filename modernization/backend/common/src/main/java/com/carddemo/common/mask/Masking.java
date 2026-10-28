package com.carddemo.common.mask;

/** C-MASK and B-MASK renderings of sensitive values. */
public final class Masking {

    private Masking() {
    }

    /** Card number as {@code ************} + last 4 (16 characters for a 16-digit number). */
    public static String card(String cardNum) {
        if (cardNum == null || cardNum.isBlank()) {
            return cardNum;
        }
        String c = cardNum.strip();
        if (c.length() <= 4) {
            return c;
        }
        return "*".repeat(c.length() - 4) + c.substring(c.length() - 4);
    }

    /** SSN on screens: {@code ***-**-} + last 4. */
    public static String ssnScreen(String ssn) {
        if (ssn == null || ssn.isBlank()) {
            return ssn;
        }
        String s = ssn.strip();
        return "***-**-" + s.substring(Math.max(0, s.length() - 4));
    }

    /** Every character except the last {@code keep} replaced by {@code *}, length kept. */
    public static String keepLast(String value, int keep) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        int n = value.length();
        if (n <= keep) {
            return value;
        }
        return "*".repeat(n - keep) + value.substring(n - keep);
    }

    /** All characters replaced by {@code *}, length kept. */
    public static String all(String value) {
        return value == null ? null : "*".repeat(value.length());
    }
}
