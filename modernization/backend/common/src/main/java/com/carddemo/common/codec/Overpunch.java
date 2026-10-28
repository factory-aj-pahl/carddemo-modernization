package com.carddemo.common.codec;

import java.math.BigDecimal;
import java.math.BigInteger;

/**
 * Signed zoned decimal with an overpunched sign in the last byte (spec/entities.md rule DF-03),
 * using the mainframe (EBCDIC) meaning of the ASCII graphics: {@code {A-I} = +0..+9},
 * {@code }J-R} = -0..-9}. Plain digits in the last byte are treated as positive (unsigned F zone).
 */
public final class Overpunch {

    private static final String POS = "{ABCDEFGHI";
    private static final String NEG = "}JKLMNOPQR";

    private Overpunch() {
    }

    public static BigDecimal decode(String field, int scale) {
        if (field == null || field.isEmpty()) {
            throw new NumberFormatException("empty zoned field");
        }
        String digits = field.substring(0, field.length() - 1);
        char last = field.charAt(field.length() - 1);
        int d;
        boolean negative;
        if ((d = POS.indexOf(last)) >= 0) {
            negative = false;
        } else if ((d = NEG.indexOf(last)) >= 0) {
            negative = true;
        } else if (last >= '0' && last <= '9') {
            d = last - '0';
            negative = false;
        } else {
            throw new NumberFormatException("invalid sign byte '" + last + "' in " + field);
        }
        for (int i = 0; i < digits.length(); i++) {
            char c = digits.charAt(i);
            if (c < '0' || c > '9') {
                throw new NumberFormatException("non-digit '" + c + "' in zoned field " + field);
            }
        }
        BigInteger unscaled = new BigInteger(digits + d);
        if (negative) {
            unscaled = unscaled.negate();
        }
        return new BigDecimal(unscaled, scale);
    }

    /**
     * Encode a value as {@code length} zoned bytes with {@code scale} implied decimals. Zero is
     * written as positive. The value is truncated (not rounded) to the field, as a COBOL MOVE does.
     */
    public static String encode(BigDecimal value, int length, int scale) {
        BigInteger unscaled = value.movePointRight(scale).toBigInteger();
        boolean negative = unscaled.signum() < 0;
        String digits = unscaled.abs().toString();
        if (digits.length() > length) {
            digits = digits.substring(digits.length() - length);
        }
        digits = "0".repeat(length - digits.length()) + digits;
        int last = digits.charAt(length - 1) - '0';
        char sign = negative ? NEG.charAt(last) : POS.charAt(last);
        return digits.substring(0, length - 1) + sign;
    }
}
