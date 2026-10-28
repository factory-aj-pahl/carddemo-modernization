package com.carddemo.common.codec;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/**
 * Parses the timestamp texts the COBOL writers produce (spec/entities.md rule DF-06, Q-ENT-08) and
 * renders them back. The text is kept as stored for display; the parsed value is for ordering.
 */
public final class LegacyTimestamps {

    /** CBTRN02C / CBACT04C Z-GET-DB2-FORMAT-TIMESTAMP: {@code YYYY-MM-DD-HH.MM.SS.hh0000}. */
    public static final DateTimeFormatter DB2 = DateTimeFormatter.ofPattern("yyyy-MM-dd-HH.mm.ss.SSSSSS");
    /** Feed and COBIL00C: {@code YYYY-MM-DD HH:MM:SS.ffffff}. */
    public static final DateTimeFormatter ISO_SPACE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSS");

    private LegacyTimestamps() {
    }

    /** Parsed value of a legacy timestamp text, or null when blank or unparseable. */
    public static LocalDateTime parse(String text) {
        if (text == null) {
            return null;
        }
        String t = text.strip();
        if (t.isEmpty()) {
            return null;
        }
        try {
            if (t.length() == 26 && t.charAt(10) == '-') {
                return LocalDateTime.parse(t, DB2);
            }
            if (t.length() == 26 && t.charAt(10) == ' ') {
                return LocalDateTime.parse(t, ISO_SPACE);
            }
            if (t.length() == 10) {
                return LocalDate.parse(t).atStartOfDay();
            }
        } catch (DateTimeParseException e) {
            return null;
        }
        return null;
    }

    /** {@code YYYY-MM-DD-HH.MM.SS.hh0000}: hundredths of a second padded with four zeros (Q-ENT-41). */
    public static String db2(LocalDateTime t) {
        int hundredths = t.getNano() / 10_000_000;
        return String.format("%04d-%02d-%02d-%02d.%02d.%02d.%02d0000", t.getYear(), t.getMonthValue(),
                t.getDayOfMonth(), t.getHour(), t.getMinute(), t.getSecond(), hundredths);
    }

    /** {@code YYYY-MM-DD HH:MM:SS.000000} as COBIL00C writes it (fraction always zero). */
    public static String cicsTimestamp(LocalDateTime t) {
        return String.format("%04d-%02d-%02d %02d:%02d:%02d.000000", t.getYear(), t.getMonthValue(),
                t.getDayOfMonth(), t.getHour(), t.getMinute(), t.getSecond());
    }
}
