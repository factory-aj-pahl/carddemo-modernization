package com.carddemo.account.internal;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * FUNCTION TEST-NUMVAL-C / NUMVAL-C for the COACTUPC amount fields: optional leading sign, optional
 * {@code $}, digits with grouping commas and an optional decimal point, optional trailing sign,
 * {@code CR} or {@code DB} (only one sign), with spaces around the parts.
 */
public final class Numval {

    private static final Pattern FORMAT = Pattern.compile(
        " *(?<lead>[+-])? *(?<cs>\\$)? *(?<num>[0-9][0-9,]*(?:\\.[0-9]*)?|\\.[0-9]+) *(?<trail>[+-]|CR|DB)? *");

    private Numval() {
    }

    /**
     * The value when TEST-NUMVAL-C accepts the text, with the Q-COACTUPC-15 limit of 10 integer digits
     * and 2 decimal places applied; empty otherwise.
     */
    public static Optional<BigDecimal> amount(String text) {
        if (text == null) {
            return Optional.empty();
        }
        Matcher m = FORMAT.matcher(text);
        if (!m.matches() || (m.group("lead") != null && m.group("trail") != null)) {
            return Optional.empty();
        }
        String num = m.group("num").replace(",", "");
        BigDecimal v = new BigDecimal(num.startsWith(".") ? "0" + num : num.endsWith(".") ? num + "0" : num);
        if (v.scale() > 2 || v.precision() - v.scale() > 10) {
            return Optional.empty();
        }
        String sign = m.group("lead") != null ? m.group("lead") : m.group("trail");
        boolean negative = "-".equals(sign) || "CR".equals(sign) || "DB".equals(sign);
        return Optional.of(negative ? v.negate() : v);
    }
}
