package com.carddemo.dataexchange.internal;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.AccessDeniedException;
import java.nio.file.NoSuchFileException;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.BadSqlGrammarException;

/**
 * File statuses for target failures, and the 9910-DISPLAY-IO-STATUS line of the listing programs.
 * Mapping: a missing input file or table is {@code 35}; an output that cannot be created is {@code 37};
 * a full disk on write is {@code 34}; any other I/O failure is {@code 30}; a database that cannot be
 * reached is {@code 93} (resource not available).
 */
final class FileStatuses {

    private static final Charset EBCDIC = Charset.forName("IBM037");

    private FileStatuses() {
    }

    static String openInput(IOException e) {
        if (e instanceof NoSuchFileException) {
            return "35";
        }
        return e instanceof AccessDeniedException ? "37" : "30";
    }

    static String openOutput(IOException e) {
        return "37";
    }

    static String write(IOException e) {
        String m = e.getMessage();
        return m != null && m.contains("No space left") ? "34" : "30";
    }

    static String table(DataAccessException e) {
        return e instanceof BadSqlGrammarException ? "35" : "93";
    }

    /**
     * {@code FILE STATUS IS: NNNN} + 4 characters (ERR-CBACT02C-04): a non-numeric status or one starting
     * with {@code 9} shows its first byte and the 3-digit unsigned binary value of its second byte as
     * stored on z/OS (EBCDIC, so {@code 93} shows {@code 9243}); any other status shows {@code 00} + status.
     */
    static String statusLine(String status) {
        char s1 = status.charAt(0);
        char s2 = status.charAt(1);
        boolean numeric = isDigit(s1) && isDigit(s2);
        if (!numeric || s1 == '9') {
            int value = String.valueOf(s2).getBytes(EBCDIC)[0] & 0xFF;
            return "FILE STATUS IS: NNNN" + s1 + String.format("%03d", value);
        }
        return "FILE STATUS IS: NNNN00" + status;
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }
}
