package com.carddemo.migration;

import com.carddemo.common.codec.Packed;
import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.Arrays;

/**
 * Byte layouts of the pending-authorization IMS segments in code page 037: the 100-byte summary root
 * {@code PAUTSUM0} (CIPAUSMY) and the 200-byte detail child {@code PAUTDTL1} (CIPAUDTY), as the IMS
 * unload programs (PAUDBUNL, DBUNLDGS), the load program (PAUDBLOD) and the DFSURGU0 sample unload
 * carry them (spec/entities.md ENT-AUTH-SUMMARY, ENT-AUTH-DETAIL).
 */
public final class PendingAuthSegments {

    public static final Charset CP037 = Charset.forName("IBM037");
    public static final int SUMMARY_LENGTH = 100;
    public static final int DETAIL_LENGTH = 200;
    /** The IMS root key: PA-ACCT-ID S9(11) COMP-3. */
    public static final int ROOT_KEY_LENGTH = 6;

    private static final byte SPACE = 0x40;
    private static final DateTimeFormatter FRAUD_DATE = DateTimeFormatter.ofPattern("MM/dd/uu").withResolverStyle(ResolverStyle.STRICT);

    private PendingAuthSegments() {
    }

    /** ENT-AUTH-SUMMARY; the legacy fields are NULL for rows created by the target. */
    public record Summary(long accountId, int customerId, String authStatus, String accountStatus, BigDecimal creditLimit,
            BigDecimal cashLimit, BigDecimal creditBalance, BigDecimal cashBalance, int approvedCount, int declinedCount,
            BigDecimal approvedAmt, BigDecimal declinedAmt, byte[] legacyStatus, byte[] legacyFiller, Integer legacyRba) {
    }

    /** ENT-AUTH-DETAIL; {@code authId} is 0 for a segment not yet stored. */
    public record Detail(long authId, long accountId, LocalDateTime authTs, String origDate, String origTime, String cardNum,
            String authType, String cardExpiryDate, String messageType, String messageSource, String authIdCode,
            String respCode, String respReason, String processingCode, BigDecimal transactionAmt, BigDecimal approvedAmt,
            String merchantCategoryCode, String acqrCountryCode, String posEntryMode, String merchantId, String merchantName,
            String merchantCity, String merchantState, String merchantZip, String transactionId, String matchStatus,
            String authFraud, LocalDate fraudRptDate, byte[] legacyFiller, Integer legacyRba) {

        public Detail withAccount(long account) {
            return new Detail(authId, account, authTs, origDate, origTime, cardNum, authType, cardExpiryDate, messageType,
                messageSource, authIdCode, respCode, respReason, processingCode, transactionAmt, approvedAmt,
                merchantCategoryCode, acqrCountryCode, posEntryMode, merchantId, merchantName, merchantCity, merchantState,
                merchantZip, transactionId, matchStatus, authFraud, fraudRptDate, legacyFiller, legacyRba);
        }
    }

    /** The 6-byte packed root key of an account (e.g. account 7 is X'00000000007C'). */
    public static byte[] rootKey(long accountId) {
        return Packed.encode(BigDecimal.valueOf(accountId), 0, ROOT_KEY_LENGTH);
    }

    /** True when the 6 bytes at {@code off} are a valid positive packed account number. */
    public static boolean validRootKey(byte[] data, int off) {
        if (!Packed.isValid(data, off, ROOT_KEY_LENGTH)) {
            return false;
        }
        return Packed.decode(data, off, ROOT_KEY_LENGTH, 0).signum() >= 0;
    }

    public static Summary decodeSummary(byte[] b, int off, Integer rba) {
        long acct = Packed.decode(b, off, 6, 0).longValueExact();
        int cust = Integer.parseInt(text(b, off + 6, 9, "PA-CUST-ID"));
        byte[] status = Arrays.copyOfRange(b, off + 15, off + 26);
        String authStatus = "";
        String accountStatus = "";
        byte[] legacyStatus = null;
        if (isText(status)) {
            String s = new String(status, CP037);
            authStatus = s.substring(0, 1).stripTrailing();
            accountStatus = s.substring(1).stripTrailing();
        } else {
            legacyStatus = status;
        }
        return new Summary(acct, cust, authStatus, accountStatus, Packed.decode(b, off + 26, 6, 2), Packed.decode(b, off + 32, 6, 2),
            Packed.decode(b, off + 38, 6, 2), Packed.decode(b, off + 44, 6, 2), binary2(b, off + 50), binary2(b, off + 52),
            Packed.decode(b, off + 54, 6, 2), Packed.decode(b, off + 60, 6, 2), legacyStatus, filler(b, off + 66, 34), rba);
    }

    public static byte[] encodeSummary(Summary s) {
        byte[] b = new byte[SUMMARY_LENGTH];
        Packed.encode(BigDecimal.valueOf(s.accountId()), 0, b, 0, 6);
        put(b, 6, 9, String.format("%09d", s.customerId()));
        if (s.legacyStatus() != null) {
            System.arraycopy(s.legacyStatus(), 0, b, 15, 11);
        } else {
            put(b, 15, 1, s.authStatus());
            put(b, 16, 10, s.accountStatus());
        }
        Packed.encode(s.creditLimit(), 2, b, 26, 6);
        Packed.encode(s.cashLimit(), 2, b, 32, 6);
        Packed.encode(s.creditBalance(), 2, b, 38, 6);
        Packed.encode(s.cashBalance(), 2, b, 44, 6);
        putBinary2(b, 50, s.approvedCount());
        putBinary2(b, 52, s.declinedCount());
        Packed.encode(s.approvedAmt(), 2, b, 54, 6);
        Packed.encode(s.declinedAmt(), 2, b, 60, 6);
        putFiller(b, 66, 34, s.legacyFiller());
        return b;
    }

    public static Detail decodeDetail(byte[] b, int off, long accountId, Integer rba) {
        int date9c = Packed.decode(b, off, 3, 0).intValueExact();
        long time9c = Packed.decode(b, off + 3, 5, 0).longValueExact();
        String fraudDate = text(b, off + 175, 8, "PA-FRAUD-RPT-DATE");
        LocalDate rpt;
        try {
            rpt = fraudDate.isEmpty() ? null : LocalDate.parse(fraudDate, FRAUD_DATE);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("PA-FRAUD-RPT-DATE '" + fraudDate + "' is not MM/DD/YY", e);
        }
        return new Detail(0, accountId, timestampOf(date9c, time9c), text(b, off + 8, 6, "PA-AUTH-ORIG-DATE"),
            text(b, off + 14, 6, "PA-AUTH-ORIG-TIME"), text(b, off + 20, 16, "PA-CARD-NUM"), text(b, off + 36, 4, "PA-AUTH-TYPE"),
            text(b, off + 40, 4, "PA-CARD-EXPIRY-DATE"), text(b, off + 44, 6, "PA-MESSAGE-TYPE"),
            text(b, off + 50, 6, "PA-MESSAGE-SOURCE"), text(b, off + 56, 6, "PA-AUTH-ID-CODE"),
            text(b, off + 62, 2, "PA-AUTH-RESP-CODE"), text(b, off + 64, 4, "PA-AUTH-RESP-REASON"),
            text(b, off + 68, 6, "PA-PROCESSING-CODE"), Packed.decode(b, off + 74, 7, 2), Packed.decode(b, off + 81, 7, 2),
            text(b, off + 88, 4, "PA-MERCHANT-CATAGORY-CODE"), text(b, off + 92, 3, "PA-ACQR-COUNTRY-CODE"),
            text(b, off + 95, 2, "PA-POS-ENTRY-MODE"), text(b, off + 97, 15, "PA-MERCHANT-ID"),
            text(b, off + 112, 22, "PA-MERCHANT-NAME"), text(b, off + 134, 13, "PA-MERCHANT-CITY"),
            text(b, off + 147, 2, "PA-MERCHANT-STATE"), text(b, off + 149, 9, "PA-MERCHANT-ZIP"),
            text(b, off + 158, 15, "PA-TRANSACTION-ID"), text(b, off + 173, 1, "PA-MATCH-STATUS"),
            text(b, off + 174, 1, "PA-AUTH-FRAUD"), rpt, filler(b, off + 183, 17), rba);
    }

    public static byte[] encodeDetail(Detail d) {
        byte[] b = new byte[DETAIL_LENGTH];
        System.arraycopy(detailKey(d.authTs()), 0, b, 0, 8);
        put(b, 8, 6, d.origDate());
        put(b, 14, 6, d.origTime());
        put(b, 20, 16, d.cardNum());
        put(b, 36, 4, d.authType());
        put(b, 40, 4, d.cardExpiryDate());
        put(b, 44, 6, d.messageType());
        put(b, 50, 6, d.messageSource());
        put(b, 56, 6, d.authIdCode());
        put(b, 62, 2, d.respCode());
        put(b, 64, 4, d.respReason());
        put(b, 68, 6, d.processingCode());
        Packed.encode(d.transactionAmt(), 2, b, 74, 7);
        Packed.encode(d.approvedAmt(), 2, b, 81, 7);
        put(b, 88, 4, d.merchantCategoryCode());
        put(b, 92, 3, d.acqrCountryCode());
        put(b, 95, 2, d.posEntryMode());
        put(b, 97, 15, d.merchantId());
        put(b, 112, 22, d.merchantName());
        put(b, 134, 13, d.merchantCity());
        put(b, 147, 2, d.merchantState());
        put(b, 149, 9, d.merchantZip());
        put(b, 158, 15, d.transactionId());
        put(b, 173, 1, d.matchStatus());
        put(b, 174, 1, d.authFraud());
        put(b, 175, 8, d.fraudRptDate() == null ? "" : d.fraudRptDate().format(FRAUD_DATE));
        putFiller(b, 183, 17, d.legacyFiller());
        return b;
    }

    /**
     * The 8-byte IMS detail key {@code PA-AUTH-DATE-9C} / {@code PA-AUTH-TIME-9C}: 99999 - YYDDD and
     * 999999999 - HHMMSSmmm of the insert timestamp (ascending key = newest first).
     */
    public static byte[] detailKey(LocalDateTime ts) {
        byte[] b = new byte[8];
        int yyddd = (ts.getYear() % 100) * 1000 + ts.getDayOfYear();
        long hhmmssmmm = ts.getHour() * 10_000_000L + ts.getMinute() * 100_000L + ts.getSecond() * 1000L + ts.getNano() / 1_000_000;
        Packed.encode(BigDecimal.valueOf(99999 - yyddd), 0, b, 0, 3);
        Packed.encode(BigDecimal.valueOf(999_999_999L - hhmmssmmm), 0, b, 3, 5);
        return b;
    }

    /** Inverse of {@link #detailKey}; the 2-digit year is 20YY (Q-COPAUS2C-02). */
    public static LocalDateTime timestampOf(int date9c, long time9c) {
        int yyddd = 99999 - date9c;
        long t = 999_999_999L - time9c;
        return LocalDate.ofYearDay(2000 + yyddd / 1000, yyddd % 1000)
            .atTime((int) (t / 10_000_000), (int) (t / 100_000 % 100), (int) (t / 1000 % 100), (int) (t % 1000) * 1_000_000);
    }

    private static boolean isText(byte[] raw) {
        for (byte x : raw) {
            if ((x & 0xFF) < 0x40) {
                return false;
            }
        }
        return Arrays.equals(new String(raw, CP037).getBytes(CP037), raw);
    }

    private static String text(byte[] b, int off, int len, String field) {
        byte[] raw = Arrays.copyOfRange(b, off, off + len);
        if (!isText(raw)) {
            throw new IllegalArgumentException(field + " holds non-text bytes");
        }
        return new String(raw, CP037).stripTrailing();
    }

    private static byte[] filler(byte[] b, int off, int len) {
        for (int i = off; i < off + len; i++) {
            if (b[i] != SPACE) {
                return Arrays.copyOfRange(b, off, off + len);
            }
        }
        return null;
    }

    private static void put(byte[] b, int off, int len, String value) {
        String v = value == null ? "" : value;
        byte[] t = (v.length() > len ? v.substring(0, len) : v).getBytes(CP037);
        Arrays.fill(b, off, off + len, SPACE);
        System.arraycopy(t, 0, b, off, t.length);
    }

    private static void putFiller(byte[] b, int off, int len, byte[] legacy) {
        if (legacy != null) {
            System.arraycopy(legacy, 0, b, off, len);
        } else {
            Arrays.fill(b, off, off + len, SPACE);
        }
    }

    private static int binary2(byte[] b, int off) {
        return (short) (((b[off] & 0xFF) << 8) | (b[off + 1] & 0xFF));
    }

    private static void putBinary2(byte[] b, int off, int v) {
        b[off] = (byte) (v >> 8);
        b[off + 1] = (byte) v;
    }
}
