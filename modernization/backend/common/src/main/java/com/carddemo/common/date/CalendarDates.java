package com.carddemo.common.date;

import java.time.LocalDate;
import java.time.YearMonth;

/**
 * CSUTLDTC (CEEDAYS) as a library: is a character date a real Gregorian calendar date in the given
 * picture? The result keeps the three outcomes callers need (Q-CSUTLDTC-02): valid, outside the
 * supported range 1582-10-15 to 9999-12-31, or otherwise invalid. Under DF-8 every caller treats
 * {@link Result#OUT_OF_RANGE} as invalid.
 */
public final class CalendarDates {

    /** Picture {@code YYYY-MM-DD} (COTRN02C, CORPT00C). */
    public static final String ISO = "YYYY-MM-DD";
    /** Picture {@code YYYYMMDD} (COACTUPC through CSUTLDPY). */
    public static final String COMPACT = "YYYYMMDD";

    /** First day CEEDAYS supports (BR-CSUTLDTC-01). */
    public static final LocalDate FIRST_SUPPORTED = LocalDate.of(1582, 10, 15);

    public enum Result {
        /** Severity 0. */
        VALID,
        /** CEEDAYS message 2513: a calendar date before 1582-10-15. */
        OUT_OF_RANGE,
        /** Any other non-zero severity (bad value, bad month, non-numeric data, year zero). */
        INVALID
    }

    private CalendarDates() {
    }

    public static Result check(String text, String picture) {
        if (text == null) {
            return Result.INVALID;
        }
        String y;
        String m;
        String d;
        switch (picture) {
            case ISO -> {
                if (text.length() != 10 || text.charAt(4) != '-' || text.charAt(7) != '-') {
                    return Result.INVALID;
                }
                y = text.substring(0, 4);
                m = text.substring(5, 7);
                d = text.substring(8, 10);
            }
            case COMPACT -> {
                if (text.length() != 8) {
                    return Result.INVALID;
                }
                y = text.substring(0, 4);
                m = text.substring(4, 6);
                d = text.substring(6, 8);
            }
            default -> throw new IllegalArgumentException("Unsupported date picture " + picture);
        }
        if (!digits(y) || !digits(m) || !digits(d)) {
            return Result.INVALID;
        }
        int year = Integer.parseInt(y);
        int month = Integer.parseInt(m);
        int day = Integer.parseInt(d);
        if (year == 0 || month < 1 || month > 12 || day < 1 || day > YearMonth.of(year, month).lengthOfMonth()) {
            return Result.INVALID;
        }
        return LocalDate.of(year, month, day).isBefore(FIRST_SUPPORTED) ? Result.OUT_OF_RANGE : Result.VALID;
    }

    /** True only for {@link Result#VALID} (DF-8: out-of-range dates are invalid for every caller). */
    public static boolean isValid(String text, String picture) {
        return check(text, picture) == Result.VALID;
    }

    private static boolean digits(String s) {
        return !s.isEmpty() && s.chars().allMatch(c -> c >= '0' && c <= '9');
    }
}
