package com.carddemo.migration;

import com.carddemo.common.codec.FixedWidth;
import com.carddemo.common.codec.LegacyTimestamps;
import com.carddemo.common.codec.Overpunch;
import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

/** Decodes a fixed-width record into column values and encodes column values back. */
public final class RecordCodec {

    private RecordCodec() {
    }

    public static Map<String, Object> decode(Layout layout, String line) {
        String rec = FixedWidth.normalize(line, layout.recordLength());
        Map<String, Object> values = new LinkedHashMap<>();
        for (Layout.Field f : layout.fields()) {
            String raw = rec.substring(f.offset(), f.offset() + f.length());
            switch (f.kind()) {
                case TEXT -> values.put(f.column(), FixedWidth.text(rec, f.offset(), f.length()));
                case UNSIGNED -> values.put(f.column(), Long.parseLong(raw));
                case SIGNED -> values.put(f.column(), Overpunch.decode(raw, f.scale()));
                case DATE -> values.put(f.column(), raw.isBlank() ? null : LocalDate.parse(raw));
                case TIMESTAMP_TEXT -> {
                    String t = FixedWidth.text(rec, f.offset(), f.length());
                    values.put(f.column(), t);
                    values.put(f.parsedTo(), LegacyTimestamps.parse(t));
                }
            }
        }
        return values;
    }

    public static String encode(Layout layout, Map<String, Object> row) {
        char[] rec = " ".repeat(layout.recordLength()).toCharArray();
        for (Layout.Field f : layout.fields()) {
            Object v = row.get(f.column());
            String s = switch (f.kind()) {
                case TEXT, TIMESTAMP_TEXT -> FixedWidth.x(v == null ? "" : v.toString(), f.length());
                case UNSIGNED -> FixedWidth.n(((Number) v).longValue(), f.length());
                case SIGNED -> Overpunch.encode(toDecimal(v), f.length(), f.scale());
                case DATE -> v == null ? " ".repeat(10) : toDate(v).toString();
            };
            s.getChars(0, f.length(), rec, f.offset());
        }
        return new String(rec);
    }

    private static BigDecimal toDecimal(Object v) {
        return v instanceof BigDecimal b ? b : new BigDecimal(v.toString());
    }

    private static LocalDate toDate(Object v) {
        if (v instanceof LocalDate d) {
            return d;
        }
        if (v instanceof Date d) {
            return d.toLocalDate();
        }
        return LocalDate.parse(v.toString());
    }
}
