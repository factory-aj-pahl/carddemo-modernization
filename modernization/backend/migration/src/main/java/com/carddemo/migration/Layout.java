package com.carddemo.migration;

import java.util.List;

/**
 * A legacy fixed-width record layout mapped to a table. Field offsets are zero-based copybook
 * offsets; bytes not covered by a field are FILLER, which the loader ignores (Q-ENT-12) and the
 * exporter writes as spaces.
 */
public record Layout(String fileName, String table, int recordLength, List<Field> fields, String orderBy) {

    public enum Kind { TEXT, UNSIGNED, SIGNED, DATE, TIMESTAMP_TEXT }

    /**
     * @param column   table column
     * @param offset   zero-based offset in the record
     * @param length   bytes in the record
     * @param kind     encoding
     * @param scale    implied decimals for SIGNED
     * @param parsedTo for TIMESTAMP_TEXT, the column that receives the parsed value
     */
    public record Field(String column, int offset, int length, Kind kind, int scale, String parsedTo) {
        static Field x(String c, int o, int l) {
            return new Field(c, o, l, Kind.TEXT, 0, null);
        }

        static Field n(String c, int o, int l) {
            return new Field(c, o, l, Kind.UNSIGNED, 0, null);
        }

        static Field s(String c, int o, int l, int scale) {
            return new Field(c, o, l, Kind.SIGNED, scale, null);
        }

        static Field d(String c, int o) {
            return new Field(c, o, 10, Kind.DATE, 0, null);
        }

        static Field ts(String c, int o, String parsed) {
            return new Field(c, o, 26, Kind.TIMESTAMP_TEXT, 0, parsed);
        }
    }
}
