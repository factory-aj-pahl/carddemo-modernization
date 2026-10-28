package com.carddemo.common.format;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Renderings of numeric edited pictures used on screens and reports. */
public final class Pic {

    private Pic() {
    }

    /**
     * {@code +9(intDigits).99}: sign always shown, leading zeros kept, truncated like a COBOL MOVE.
     * Example: {@code signedZeros(194, 10)} gives {@code +0000000194.00}.
     */
    public static String signedZeros(BigDecimal value, int intDigits) {
        BigDecimal v = value.setScale(2, RoundingMode.DOWN);
        String sign = v.signum() < 0 ? "-" : "+";
        String abs = v.abs().toPlainString();
        int dot = abs.indexOf('.');
        String ip = abs.substring(0, dot);
        if (ip.length() > intDigits) {
            ip = ip.substring(ip.length() - intDigits);
        }
        return sign + "0".repeat(intDigits - ip.length()) + ip + abs.substring(dot);
    }

    /** Account ID as 11 digits with leading zeros. */
    public static String accountId(long id) {
        return String.format("%011d", id);
    }
}
