package com.carddemo.dataexchange.internal;

import java.math.BigInteger;

/**
 * COBOL binary (COMP, big-endian two's complement) and packed decimal (COMP-3) fields, as GnuCOBOL with
 * {@code -std=ibm} and z/OS store them. Decoders throw {@link NumberFormatException} for a value the
 * field's picture cannot hold (Q-CBIMPORT-07).
 */
final class Comp {

    private Comp() {
    }

    static void binary(byte[] rec, int offset, int length, long value) {
        long v = value;
        for (int i = length - 1; i >= 0; i--) {
            rec[offset + i] = (byte) v;
            v >>= 8;
        }
    }

    /** Unsigned binary field with picture 9(digits). */
    static long unsignedBinary(byte[] rec, int offset, int length, int digits) {
        long v = 0;
        for (int i = 0; i < length; i++) {
            v = (v << 8) | (rec[offset + i] & 0xFF);
        }
        if (v < 0 || v >= BigInteger.TEN.pow(digits).longValueExact()) {
            throw new NumberFormatException("binary value out of range for 9(" + digits + ")");
        }
        return v;
    }

    /** Signed binary field with picture S9(digits) (implied decimals do not change the stored integer). */
    static long signedBinary(byte[] rec, int offset, int length, int digits) {
        long v = rec[offset] < 0 ? -1 : 0;
        for (int i = 0; i < length; i++) {
            v = (v << 8) | (rec[offset + i] & 0xFF);
        }
        if (Math.abs(v) >= BigInteger.TEN.pow(digits).longValueExact()) {
            throw new NumberFormatException("binary value out of range for S9(" + digits + ")");
        }
        return v;
    }

    /**
     * Packed decimal of {@code length} bytes holding {@code digits} digits (high-order digits beyond the
     * picture are dropped, as a COBOL MOVE does). Sign nibble {@code C}/{@code D} when signed, {@code F}
     * when unsigned.
     */
    static void packed(byte[] rec, int offset, int length, BigInteger unscaled, int digits, boolean signed) {
        BigInteger abs = unscaled.abs().mod(BigInteger.TEN.pow(digits));
        String d = abs.toString();
        int nibbles = length * 2 - 1;
        d = "0".repeat(nibbles - d.length()) + d;
        int sign = !signed ? 0xF : unscaled.signum() < 0 ? 0xD : 0xC;
        for (int i = 0; i < length; i++) {
            int hi = d.charAt(2 * i) - '0';
            int lo = 2 * i + 1 < nibbles ? d.charAt(2 * i + 1) - '0' : sign;
            rec[offset + i] = (byte) ((hi << 4) | lo);
        }
    }

    /** Value of a packed decimal field (unscaled); signs A, C, E, F are positive and B, D negative. */
    static BigInteger packedValue(byte[] rec, int offset, int length) {
        StringBuilder digits = new StringBuilder();
        for (int i = 0; i < length; i++) {
            int b = rec[offset + i] & 0xFF;
            int hi = b >> 4;
            int lo = b & 0xF;
            if (hi > 9) {
                throw new NumberFormatException("invalid packed digit");
            }
            digits.append((char) ('0' + hi));
            if (i < length - 1) {
                if (lo > 9) {
                    throw new NumberFormatException("invalid packed digit");
                }
                digits.append((char) ('0' + lo));
            } else if (lo < 0xA) {
                throw new NumberFormatException("invalid packed sign");
            } else {
                BigInteger v = new BigInteger(digits.toString());
                return lo == 0xB || lo == 0xD ? v.negate() : v;
            }
        }
        throw new NumberFormatException("empty packed field");
    }
}
