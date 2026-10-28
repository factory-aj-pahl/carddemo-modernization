package com.carddemo.common.codec;

import java.math.BigDecimal;
import java.math.BigInteger;

/**
 * COBOL {@code COMP-3} (packed decimal): two digits per byte, sign in the low nibble of the last byte
 * ({@code C} or {@code F} positive, {@code D} negative). Encoding writes {@code C} for zero and positive
 * values and {@code D} for negative ones, as a signed field ({@code S9...}) holds them.
 */
public final class Packed {

    private Packed() {
    }

    /** Byte length of a packed field with {@code digits} digits. */
    public static int length(int digits) {
        return digits / 2 + 1;
    }

    /** Decodes {@code len} bytes at {@code off}; throws {@link NumberFormatException} on an invalid digit or sign nibble. */
    public static BigDecimal decode(byte[] data, int off, int len, int scale) {
        StringBuilder digits = new StringBuilder(len * 2);
        for (int i = 0; i < len; i++) {
            int b = data[off + i] & 0xFF;
            int hi = b >> 4;
            int lo = b & 0x0F;
            digit(digits, hi);
            if (i < len - 1) {
                digit(digits, lo);
            } else if (lo == 0x0D || lo == 0x0B) {
                return new BigDecimal(new BigInteger(digits.toString()).negate(), scale);
            } else if (lo < 0x0A) {
                throw new NumberFormatException("invalid packed sign nibble " + Integer.toHexString(lo));
            }
        }
        return new BigDecimal(new BigInteger(digits.toString()), scale);
    }

    /** True when the bytes are a valid packed field. */
    public static boolean isValid(byte[] data, int off, int len) {
        try {
            decode(data, off, len, 0);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static void digit(StringBuilder sb, int nibble) {
        if (nibble > 9) {
            throw new NumberFormatException("invalid packed digit nibble " + Integer.toHexString(nibble));
        }
        sb.append((char) ('0' + nibble));
    }

    /** Encodes into {@code len} bytes at {@code off}; high-order digits that do not fit are dropped, as a COBOL MOVE does. */
    public static void encode(BigDecimal value, int scale, byte[] out, int off, int len) {
        BigInteger unscaled = value.movePointRight(scale).toBigInteger();
        boolean negative = unscaled.signum() < 0;
        String digits = unscaled.abs().toString();
        int n = len * 2 - 1;
        if (digits.length() > n) {
            digits = digits.substring(digits.length() - n);
        }
        digits = "0".repeat(n - digits.length()) + digits + (negative ? 'D' : 'C');
        for (int i = 0; i < len; i++) {
            out[off + i] = (byte) ((Character.digit(digits.charAt(2 * i), 16) << 4) | Character.digit(digits.charAt(2 * i + 1), 16));
        }
    }

    public static byte[] encode(BigDecimal value, int scale, int len) {
        byte[] out = new byte[len];
        encode(value, scale, out, 0, len);
        return out;
    }
}
