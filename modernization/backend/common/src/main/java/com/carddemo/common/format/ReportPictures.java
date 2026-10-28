package com.carddemo.common.format;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;

/**
 * COBOL numeric edited pictures and DISPLAY renderings used by batch reports and statements, matching
 * GnuCOBOL output byte for byte. Values that do not fit the picture throw {@link PictureOverflow} instead of
 * losing high-order digits (Q-CBTRN03C-10, Q-CBSTM03A-09).
 */
public final class ReportPictures {

    private static final BigDecimal BILLION = BigDecimal.TEN.pow(9);

    private ReportPictures() {
    }

    /** A value too large for its edited picture. */
    public static final class PictureOverflow extends RuntimeException {
        public PictureOverflow(String picture, BigDecimal value) {
            super(value.toPlainString() + " does not fit PIC " + picture);
        }
    }

    /**
     * {@code -ZZZ,ZZZ,ZZZ.ZZ} (sign {@code '-'}) or {@code +ZZZ,ZZZ,ZZZ.ZZ} (sign {@code '+'}): 15 characters.
     * Zero is all spaces (every digit position is Z); otherwise leading zeros and their commas are blanked and
     * the decimal point and the two decimals are always shown.
     */
    public static String commaAmount(BigDecimal value, char sign) {
        BigDecimal v = value.setScale(2, RoundingMode.DOWN);
        String picture = sign + "ZZZ,ZZZ,ZZZ.ZZ";
        if (v.abs().compareTo(BILLION) >= 0) {
            throw new PictureOverflow(picture, value);
        }
        if (v.signum() == 0) {
            return " ".repeat(15);
        }
        String digits = digits(v, 11);
        StringBuilder sb = new StringBuilder(15);
        sb.append(v.signum() < 0 ? '-' : (sign == '+' ? '+' : ' '));
        boolean significant = false;
        int d = 0;
        for (char c : "ZZZ,ZZZ,ZZZ".toCharArray()) {
            if (c == ',') {
                sb.append(significant ? ',' : ' ');
            } else {
                char digit = digits.charAt(d++);
                significant |= digit != '0';
                sb.append(significant ? digit : ' ');
            }
        }
        sb.append('.').append(digits, 9, 11);
        return sb.toString();
    }

    /** {@code Z(9).99-}: 13 characters, leading zeros blanked, trailing {@code -} when negative. */
    public static String zAmount(BigDecimal value) {
        BigDecimal v = value.setScale(2, RoundingMode.DOWN);
        if (v.abs().compareTo(BILLION) >= 0) {
            throw new PictureOverflow("Z(9).99-", value);
        }
        String digits = digits(v, 11);
        String integer = digits.substring(0, 9).replaceFirst("^0+", "");
        return " ".repeat(9 - integer.length()) + integer + "." + digits.substring(9) + (v.signum() < 0 ? "-" : " ");
    }

    /** {@code 9(9).99-}: 13 characters, leading zeros kept, trailing {@code -} when negative. */
    public static String nineAmount(BigDecimal value) {
        BigDecimal v = value.setScale(2, RoundingMode.DOWN);
        if (v.abs().compareTo(BILLION) >= 0) {
            throw new PictureOverflow("9(9).99-", value);
        }
        String digits = digits(v, 11);
        return digits.substring(0, 9) + "." + digits.substring(9) + (v.signum() < 0 ? "-" : " ");
    }

    /**
     * GnuCOBOL {@code DISPLAY} of a signed zoned {@code S9(intDigits)V9(scale)} item: all digits without the
     * decimal point, followed by the sign ({@code 00000000005+}). High-order digits beyond the item are lost,
     * as they are in the COBOL field.
     */
    public static String display(BigDecimal value, int intDigits, int scale) {
        BigDecimal v = value.setScale(scale, RoundingMode.DOWN);
        BigInteger unscaled = v.unscaledValue().abs().mod(BigInteger.TEN.pow(intDigits + scale));
        String s = unscaled.toString();
        return "0".repeat(intDigits + scale - s.length()) + s + (v.signum() < 0 && unscaled.signum() != 0 ? "-" : "+");
    }

    private static String digits(BigDecimal v, int length) {
        String s = v.abs().unscaledValue().toString();
        return "0".repeat(length - s.length()) + s;
    }
}
