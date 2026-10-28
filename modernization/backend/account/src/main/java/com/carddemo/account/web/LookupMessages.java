package com.carddemo.account.web;

import com.carddemo.common.format.Pic;

/** Verbatim lookup messages shared by COACTVWC and COACTUPC (Q-COACTVWC-05: kept with their CICS codes). */
final class LookupMessages {

    /** DFHRESP(NOTFND) and its RESP2 as the COBOL formats them (9 digits and a space). */
    private static final String NOTFND = "000000013 ";
    private static final String NOTFND_REASON = "000000080 ";
    /** A database failure is reported as DFHRESP(IOERR). */
    private static final String IOERR = "000000017 ";
    private static final String NO_REASON = "000000000 ";
    private static final int MSG_LEN = 75;

    private LookupMessages() {
    }

    static String xrefNotFound(long accountId) {
        return cut("Account:" + Pic.accountId(accountId) + " not found in" + " Cross ref file.  Resp:" + NOTFND + " Reas:"
            + NOTFND_REASON);
    }

    static String accountNotFound(long accountId) {
        return cut("Account:" + Pic.accountId(accountId) + " not found in" + " Acct Master file.Resp:" + NOTFND + " Reas:"
            + NOTFND_REASON);
    }

    static String customerNotFound(long customerId) {
        return cut("CustId:" + String.format("%09d", customerId) + " not found" + " in customer master.Resp: " + NOTFND
            + " REAS:" + NOTFND_REASON);
    }

    /** WS-FILE-ERROR-MESSAGE for a failed READ of {@code file} (CXACAIX, ACCTDAT, CUSTDAT). */
    static String fileError(String file) {
        return cut("File Error: " + pad("READ", 8) + " on " + pad(file, 9) + " returned RESP " + IOERR + ",RESP2 " + NO_REASON);
    }

    private static String pad(String s, int len) {
        return String.format("%-" + len + "s", s);
    }

    private static String cut(String s) {
        return s.length() > MSG_LEN ? s.substring(0, MSG_LEN) : s;
    }
}
