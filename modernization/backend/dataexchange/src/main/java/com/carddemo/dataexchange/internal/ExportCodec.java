package com.carddemo.dataexchange.internal;

import static java.nio.charset.StandardCharsets.ISO_8859_1;

import com.carddemo.common.codec.FixedWidth;
import com.carddemo.common.codec.Overpunch;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Arrays;

/**
 * The CVEXPORT record (500 bytes) and its conversion from and to the legacy master records
 * (BR-CBEXPORT-03..08, BR-CBIMPORT-03..06). Conversions work on the legacy record bytes so the field
 * moves are the COBOL MOVEs: text and zoned fields are copied, and binary and packed fields are
 * re-encoded field by field (Q-ENT-19, Q-CBEXPORT-04). Offsets are zero-based.
 */
final class ExportCodec {

    static final int LENGTH = 500;
    static final int SEQUENCE_OFFSET = 27;
    static final String BRANCH = "0001";
    static final String REGION = "NORTH";

    private ExportCodec() {
    }

    /** BR-CBEXPORT-03: type, timestamp, 4-byte binary sequence, branch, region; data area spaces. */
    static byte[] header(char type, String timestamp, long sequence) {
        byte[] e = new byte[LENGTH];
        Arrays.fill(e, (byte) ' ');
        e[0] = (byte) type;
        put(e, 1, FixedWidth.x(timestamp, 26));
        Comp.binary(e, SEQUENCE_OFFSET, 4, sequence % 1_000_000_000L);
        put(e, 31, BRANCH);
        put(e, 35, REGION);
        return e;
    }

    static long sequence(byte[] e) {
        long v = 0;
        for (int i = 0; i < 4; i++) {
            v = (v << 8) | (e[SEQUENCE_OFFSET + i] & 0xFF);
        }
        return v;
    }

    // ---- export (legacy record -> export record) ----

    /** BR-CBEXPORT-04 from a CVCUS01Y record. */
    static byte[] customer(String r, String ts, long seq) {
        byte[] e = header('C', ts, seq);
        Comp.binary(e, 40, 4, Long.parseLong(r.substring(0, 9)));
        put(e, 44, r.substring(9, 329));
        Comp.packed(e, 364, 2, new BigInteger(r.substring(329, 332)), 3, false);
        return e;
    }

    /** BR-CBEXPORT-05 from a CVACT01Y record. */
    static byte[] account(String r, String ts, long seq) {
        byte[] e = header('A', ts, seq);
        put(e, 40, r.substring(0, 12));
        Comp.packed(e, 52, 7, unscaled(r, 12, 12), 12, true);
        put(e, 59, r.substring(24, 36));
        Comp.packed(e, 71, 7, unscaled(r, 36, 12), 12, true);
        put(e, 78, r.substring(48, 90));
        Comp.binary(e, 120, 8, unscaled(r, 90, 12).longValueExact());
        put(e, 128, r.substring(102, 122));
        return e;
    }

    /** BR-CBEXPORT-06 from a CVACT03Y record. */
    static byte[] xref(String r, String ts, long seq) {
        byte[] e = header('X', ts, seq);
        put(e, 40, r.substring(0, 25));
        Comp.binary(e, 65, 8, Long.parseLong(r.substring(25, 36)));
        return e;
    }

    /** BR-CBEXPORT-07 from a CVTRA05Y record. */
    static byte[] transaction(String r, String ts, long seq) {
        byte[] e = header('T', ts, seq);
        put(e, 40, r.substring(0, 132));
        Comp.packed(e, 172, 6, unscaled(r, 132, 11), 11, true);
        Comp.binary(e, 178, 4, Long.parseLong(r.substring(143, 152)));
        put(e, 182, r.substring(152, 330));
        return e;
    }

    /** BR-CBEXPORT-08 from a CVACT02Y record. */
    static byte[] card(String r, String ts, long seq) {
        byte[] e = header('D', ts, seq);
        put(e, 40, r.substring(0, 16));
        Comp.binary(e, 56, 8, Long.parseLong(r.substring(16, 27)));
        Comp.binary(e, 64, 2, Integer.parseInt(r.substring(27, 30)));
        put(e, 66, r.substring(30, 91));
        return e;
    }

    // ---- import (export record -> legacy record, FILLER as spaces per Q-CBIMPORT-03) ----

    /** BR-CBIMPORT-03: CVCUS01Y record (500 bytes). */
    static String toCustomer(byte[] e) {
        long id = Comp.unsignedBinary(e, 40, 4, 9);
        digits(e, 314, 9);
        BigInteger fico = Comp.packedValue(e, 364, 2).abs();
        return FixedWidth.n(id, 9) + str(e, 44, 320) + FixedWidth.n(fico.longValueExact(), 3) + " ".repeat(168);
    }

    /** BR-CBIMPORT-04: CVACT01Y record (300 bytes). */
    static String toAccount(byte[] e) {
        digits(e, 40, 11);
        return str(e, 40, 12) + amount(Comp.packedValue(e, 52, 7), 12) + zoned(str(e, 59, 12))
            + amount(Comp.packedValue(e, 71, 7), 12) + str(e, 78, 30) + zoned(str(e, 108, 12))
            + amount(BigInteger.valueOf(Comp.signedBinary(e, 120, 8, 12)), 12) + str(e, 128, 20) + " ".repeat(178);
    }

    /** BR-CBIMPORT-05: CVACT03Y record (50 bytes). */
    static String toXref(byte[] e) {
        digits(e, 56, 9);
        return str(e, 40, 25) + FixedWidth.n(Comp.unsignedBinary(e, 65, 8, 11), 11) + " ".repeat(14);
    }

    /** BR-CBIMPORT-05: CVTRA05Y record (350 bytes). */
    static String toTransaction(byte[] e) {
        digits(e, 58, 4);
        return str(e, 40, 132) + amount(Comp.packedValue(e, 172, 6), 11) + FixedWidth.n(Comp.unsignedBinary(e, 178, 4, 9), 9)
            + str(e, 182, 178) + " ".repeat(20);
    }

    /** BR-CBIMPORT-06: CVACT02Y record (150 bytes). */
    static String toCard(byte[] e) {
        return str(e, 40, 16) + FixedWidth.n(Comp.unsignedBinary(e, 56, 8, 11), 11) + FixedWidth.n(Comp.unsignedBinary(e, 64, 2, 3), 3)
            + str(e, 66, 61) + " ".repeat(59);
    }

    // ---- helpers ----

    private static BigInteger unscaled(String r, int offset, int length) {
        return Overpunch.decode(r.substring(offset, offset + length), 2).unscaledValue();
    }

    /** S9(n)V99 zoned with overpunched sign (the MOVE target of a packed or binary amount). */
    private static String amount(BigInteger unscaled, int length) {
        if (unscaled.abs().compareTo(BigInteger.TEN.pow(length)) >= 0) {
            throw new NumberFormatException("amount does not fit S9(" + (length - 2) + ")V99");
        }
        return Overpunch.encode(new BigDecimal(unscaled, 2), length, 2);
    }

    /** A zoned S9(10)V99 field moved to the same picture: the sign is normalized. */
    private static String zoned(String field) {
        return Overpunch.encode(Overpunch.decode(field, 2), field.length(), 2);
    }

    private static void digits(byte[] e, int offset, int length) {
        for (int i = offset; i < offset + length; i++) {
            if (e[i] < '0' || e[i] > '9') {
                throw new NumberFormatException("non-digit in zoned field");
            }
        }
    }

    static String str(byte[] e, int offset, int length) {
        return new String(e, offset, length, ISO_8859_1);
    }

    private static void put(byte[] e, int offset, String s) {
        byte[] b = s.getBytes(ISO_8859_1);
        System.arraycopy(b, 0, e, offset, b.length);
    }
}
