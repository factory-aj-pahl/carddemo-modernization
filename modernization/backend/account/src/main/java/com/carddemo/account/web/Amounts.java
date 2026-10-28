package com.carddemo.account.web;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;

/**
 * PICOUT {@code +ZZZ,ZZZ,ZZZ.99} as COBOL edits it (sign always shown, zero suppression up to the
 * decimal point, so zero shows as {@code +           .00}), widened to all 10 integer digits of
 * S9(10)V99 (Q-COACTVWC-08, Q-COACTUPC-30): values below 1,000,000,000.00 keep the 15-character
 * form, larger values grow to 17 characters.
 */
final class Amounts {

    private static final int WIDTH = 14;

    private Amounts() {
    }

    static String edited(BigDecimal value) {
        BigDecimal v = value.setScale(2, RoundingMode.DOWN);
        DecimalFormat f = new DecimalFormat("#,###.00", DecimalFormatSymbols.getInstance(Locale.US));
        String digits = f.format(v.abs());
        String sign = v.signum() < 0 ? "-" : "+";
        return sign + " ".repeat(Math.max(0, WIDTH - digits.length())) + digits;
    }
}
