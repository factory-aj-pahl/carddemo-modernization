package com.carddemo.inquiry.internal;

/**
 * MQ-ERR-DISPLAY of COACCT01 / CODATE01: paragraph X(25), 2 spaces, return message X(25), 2 spaces,
 * condition code 9(02), 2 spaces, reason code 9(05), 2 spaces, queue name X(48). Q-COACCT01-11 adds the
 * storage error code as its own field after the queue name (2 spaces, X(10)); it is blank when the error
 * is not a storage error. A record is built from scratch for every error (Q-COACCT01-16, Q-CODATE01-12) and
 * is sent as 1000 bytes of text, like the COBOL buffer.
 */
record ErrorRecord(String paragraph, String message, int conditionCode, int reasonCode, String queueName, String storageCode) {

    static final int LENGTH = 1000;

    /** MQCC-FAILED. */
    static final int FAILED = 2;
    /** MQRC-Q-FULL: the reply queue rejected the reply (bounded queue full). */
    static final int Q_FULL = 2053;
    /** MQRC-UNKNOWN-OBJECT-NAME: the reply queue does not exist. */
    static final int UNKNOWN_OBJECT_NAME = 2085;

    static ErrorRecord storage(String message, String queueName, String storageCode) {
        return new ErrorRecord("", message, 0, 0, queueName, storageCode);
    }

    static ErrorRecord broker(String message, int reasonCode, String queueName) {
        return new ErrorRecord("", message, FAILED, reasonCode, queueName, "");
    }

    String text() {
        String rec = x(paragraph, 25) + "  " + x(message, 25) + "  " + n(conditionCode, 2) + "  " + n(reasonCode, 5) + "  "
            + x(queueName, 48) + "  " + x(storageCode, 10);
        return rec + " ".repeat(LENGTH - rec.length());
    }

    private static String x(String s, int len) {
        String v = s == null ? "" : s;
        return v.length() >= len ? v.substring(0, len) : v + " ".repeat(len - v.length());
    }

    private static String n(int v, int len) {
        String s = Integer.toString(Math.abs(v));
        s = s.length() > len ? s.substring(s.length() - len) : s;
        return "0".repeat(len - s.length()) + s;
    }
}
