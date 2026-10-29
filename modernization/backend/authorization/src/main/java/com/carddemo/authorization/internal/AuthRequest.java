package com.carddemo.authorization.internal;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * One authorization request (CCPAURQY, spec/entities.md ENT-AUTH-REQUEST). The fields hold the text as the
 * COBOL UNSTRING leaves it in the receiving fields: cut to the field length, trailing space padding removed.
 * Every other character, control characters and bytes above 0x7E included, is kept as received.
 *
 * @param amountText the amount field as sent (WS-TRANSACTION-AMT-AN X(13))
 */
public record AuthRequest(String authDate, String authTime, String cardNum, String authType, String cardExpiryDate,
        String messageType, String messageSource, String processingCode, String amountText, String merchantCategoryCode,
        String acqrCountryCode, String posEntryMode, String merchantId, String merchantName, String merchantCity,
        String merchantState, String merchantZip, String transactionId) {

    /** The 18 fields of BR-COPAUA0C-03, in message order. */
    public static final int FIELDS = 18;
    static final int[] LENGTHS = {6, 6, 16, 4, 4, 6, 6, 6, 13, 4, 3, 2, 15, 22, 13, 2, 9, 15};
    private static final Pattern NUMBER = Pattern.compile("[+-]?(\\d+(\\.\\d*)?|\\.\\d+)|(\\d+(\\.\\d*)?|\\.\\d+)[+-]");

    public AuthRequest {
        String[] v = {authDate, authTime, cardNum, authType, cardExpiryDate, messageType, messageSource, processingCode,
            amountText, merchantCategoryCode, acqrCountryCode, posEntryMode, merchantId, merchantName, merchantCity,
            merchantState, merchantZip, transactionId};
        for (int i = 0; i < v.length; i++) {
            v[i] = fit(v[i], LENGTHS[i]);
        }
        authDate = v[0];
        authTime = v[1];
        cardNum = v[2];
        authType = v[3];
        cardExpiryDate = v[4];
        messageType = v[5];
        messageSource = v[6];
        processingCode = v[7];
        amountText = v[8];
        merchantCategoryCode = v[9];
        acqrCountryCode = v[10];
        posEntryMode = v[11];
        merchantId = v[12];
        merchantName = v[13];
        merchantCity = v[14];
        merchantState = v[15];
        merchantZip = v[16];
        transactionId = v[17];
    }

    private static String fit(String s, int len) {
        String v = s == null ? "" : s;
        String cut = v.length() > len ? v.substring(0, len) : v;
        int end = cut.length();
        while (end > 0 && cut.charAt(end - 1) == ' ') {
            end--;
        }
        return cut.substring(0, end);
    }

    /**
     * BR-COPAUA0C-03: {@code UNSTRING ... DELIMITED BY ','} into the 18 fields. The UNSTRING has no ON OVERFLOW
     * clause, so text after the 18th field is ignored, and fields the message does not reach stay as the fresh
     * request area holds them (spaces); such a request is still decided and answered.
     */
    public static AuthRequest parse(String text) {
        List<String> parts = new ArrayList<>(FIELDS);
        int start = 0;
        for (int i = 0; i <= text.length() && parts.size() < FIELDS; i++) {
            if (i == text.length() || text.charAt(i) == ',') {
                parts.add(text.substring(start, i));
                start = i + 1;
            }
        }
        while (parts.size() < FIELDS) {
            parts.add("");
        }
        return new AuthRequest(parts.get(0), parts.get(1), parts.get(2), parts.get(3), parts.get(4), parts.get(5), parts.get(6),
            parts.get(7), parts.get(8), parts.get(9), parts.get(10), parts.get(11), parts.get(12), parts.get(13), parts.get(14),
            parts.get(15), parts.get(16), parts.get(17));
    }

    /**
     * The amount as FUNCTION NUMVAL reads it into S9(10)V99 (leading or trailing sign, surrounding spaces),
     * or null when the text is not a number or does not fit 10 integer digits (Q-COPAUA0C-12).
     */
    public BigDecimal amount() {
        String t = amountText.strip();
        if (!NUMBER.matcher(t).matches()) {
            return null;
        }
        boolean negative = t.startsWith("-") || t.endsWith("-");
        String digits = t.replace("+", "").replace("-", "");
        BigDecimal v = new BigDecimal(digits.endsWith(".") ? digits + "0" : digits).setScale(2, RoundingMode.DOWN);
        if (v.compareTo(new BigDecimal("9999999999.99")) > 0) {
            return null;
        }
        return negative ? v.negate() : v;
    }
}
