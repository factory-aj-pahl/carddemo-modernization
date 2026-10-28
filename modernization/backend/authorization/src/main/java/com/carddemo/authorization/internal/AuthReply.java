package com.carddemo.authorization.internal;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * One authorization reply (CCPAURLY).
 *
 * @param authIdCode the request time HHMMSS (BR-COPAUA0C-11)
 */
public record AuthReply(String cardNum, String transactionId, String authIdCode, String respCode, String reasonCode,
        BigDecimal approvedAmount) {

    public static final String APPROVED = "00";
    public static final String DECLINED = "05";

    public boolean approved() {
        return APPROVED.equals(respCode);
    }

    /**
     * BR-COPAUA0C-12, Q-COPAUA0C-01, Q-COPAUA0C-14: {@code card,txnid,authid,resp,reason,amount,} with fixed-width
     * fields and a trailing comma; 63 characters, only this reply.
     */
    public String text() {
        return pad(cardNum, 16) + ',' + pad(transactionId, 15) + ',' + pad(authIdCode, 6) + ',' + pad(respCode, 2) + ','
            + pad(reasonCode, 4) + ',' + edited(approvedAmount) + ',';
    }

    /** {@code -zzzzzzzzz9.99}: a fixed sign position (space or minus), zero-suppressed digits, 14 characters. */
    static String edited(BigDecimal amount) {
        BigDecimal v = amount.setScale(2, RoundingMode.DOWN);
        String digits = v.abs().toPlainString();
        int dot = digits.indexOf('.');
        String ip = digits.substring(0, dot);
        if (ip.length() > 10) {
            ip = ip.substring(ip.length() - 10);
        }
        String body = ip + digits.substring(dot);
        return (v.signum() < 0 ? "-" : " ") + " ".repeat(13 - body.length()) + body;
    }

    private static String pad(String s, int len) {
        String v = s == null ? "" : s;
        return v.length() >= len ? v.substring(0, len) : v + " ".repeat(len - v.length());
    }
}
