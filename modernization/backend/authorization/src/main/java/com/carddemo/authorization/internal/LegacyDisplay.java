package com.carddemo.authorization.internal;

import java.sql.SQLException;
import java.time.LocalDate;

/** DISPLAY renderings shared by the authorization batch programs (as GnuCOBOL prints them). */
final class LegacyDisplay {

    static final String DASHES = "*-------------------------------------*";

    private LegacyDisplay() {
    }

    /** A {@code PIC S9(8) COMP} counter: sign and 8 digits, e.g. {@code +00000022}. */
    static String count(long n) {
        return (n < 0 ? "-" : "+") + String.format("%08d", Math.abs(n));
    }

    /** {@code PA-ACCT-ID PIC S9(11) COMP-3}: sign and 11 digits. */
    static String signedAccount(long id) {
        return "+" + String.format("%011d", id);
    }

    /** {@code ACCEPT ... FROM DATE}: YYMMDD. */
    static String yymmdd(LocalDate d) {
        return String.format("%02d%02d%02d", d.getYear() % 100, d.getMonthValue(), d.getDayOfMonth());
    }

    /** {@code ACCEPT ... FROM DAY}: YYDDD. */
    static String yyddd(LocalDate d) {
        return String.format("%02d%03d", d.getYear() % 100, d.getDayOfYear());
    }

    /** The status shown where the program shows a DL/I status: the SQLSTATE of the failure, else its type. */
    static String status(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof SQLException sql && sql.getSQLState() != null) {
                return sql.getSQLState();
            }
        }
        return e.getClass().getSimpleName();
    }
}
