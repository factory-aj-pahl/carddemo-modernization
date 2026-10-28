package com.carddemo.common.msg;

/** Verbatim texts of CSMSG01Y and COTTL01Y (trailing spaces are part of the text). */
public final class CommonMessages {

    /** CCDA-MSG-THANK-YOU, 50 bytes. */
    public static final String THANK_YOU = "Thank you for using CardDemo application...      ";

    /** CCDA-MSG-INVALID-KEY, 50 bytes. */
    public static final String INVALID_KEY = "Invalid key pressed. Please see below...         ";

    /** COMEN01C/COADM01C admin-only text, reused by C-ROLE (trailing space included). */
    public static final String ADMIN_ONLY = "No access - Admin Only option... ";

    public static final String TITLE01 = "      AWS Mainframe Modernization       ";
    public static final String TITLE02 = "              CardDemo                  ";

    private CommonMessages() {
    }
}
