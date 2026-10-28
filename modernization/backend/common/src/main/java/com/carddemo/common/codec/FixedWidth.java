package com.carddemo.common.codec;

import java.math.BigDecimal;

/** Helpers for fixed-width record layouts (copybook offsets are zero-based). */
public final class FixedWidth {

    private FixedWidth() {
    }

    /** Pad or cut a line to the record length (ASCII sample lines may omit trailing filler). */
    public static String normalize(String line, int length) {
        String l = line.endsWith("\r") ? line.substring(0, line.length() - 1) : line;
        if (l.length() >= length) {
            return l.substring(0, length);
        }
        return l + " ".repeat(length - l.length());
    }

    /** X(n) field with trailing spaces removed; LOW-VALUES treated as empty (rule DF-07). */
    public static String text(String rec, int offset, int length) {
        String s = rec.substring(offset, offset + length);
        if (s.chars().allMatch(c -> c == 0)) {
            return "";
        }
        return s.stripTrailing();
    }

    /** 9(n) unsigned zoned field as a long. */
    public static long unsigned(String rec, int offset, int length) {
        String s = rec.substring(offset, offset + length);
        return Long.parseLong(s);
    }

    /** S9(n)V9(scale) signed zoned field. */
    public static BigDecimal signed(String rec, int offset, int length, int scale) {
        return Overpunch.decode(rec.substring(offset, offset + length), scale);
    }

    /** Left-justified, space-padded X(n) field; longer values are cut. */
    public static String x(String value, int length) {
        String v = value == null ? "" : value;
        if (v.length() >= length) {
            return v.substring(0, length);
        }
        return v + " ".repeat(length - v.length());
    }

    /** Zero-padded 9(n) field. */
    public static String n(long value, int length) {
        String s = Long.toString(value);
        if (s.length() > length) {
            s = s.substring(s.length() - length);
        }
        return "0".repeat(length - s.length()) + s;
    }

    /** S9(n)V9(scale) zoned field with overpunched sign. */
    public static String s(BigDecimal value, int length, int scale) {
        return Overpunch.encode(value, length, scale);
    }
}
