package com.carddemo.test;

import com.carddemo.common.codec.FixedWidth;
import com.carddemo.common.codec.Overpunch;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

/** Builds DALYTRAN (CVTRA06Y) test records from the sample daily file. */
public final class DailyRecords {

    private DailyRecords() {
    }

    /** Every record of app/data/ASCII/dailytran.txt, padded to 350 bytes. */
    public static List<String> sample() {
        try {
            return Files.readAllLines(RepoPaths.asciiData().resolve("dailytran.txt"), StandardCharsets.ISO_8859_1).stream()
                .filter(l -> !l.isBlank()).map(l -> FixedWidth.normalize(l, 350)).toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The sample record with this transaction ID. */
    public static String sample(String tranId) {
        return sample().stream().filter(r -> r.startsWith(tranId)).findFirst()
            .orElseThrow(() -> new IllegalArgumentException("no sample record " + tranId));
    }

    public static String withTranId(String rec, String tranId) {
        return put(rec, 0, FixedWidth.x(tranId, 16));
    }

    public static String withCard(String rec, String card) {
        return put(rec, 262, FixedWidth.x(card, 16));
    }

    public static String withAmount(String rec, String amount) {
        return put(rec, 132, Overpunch.encode(new BigDecimal(amount), 11, 2));
    }

    public static String withOrigTs(String rec, String ts) {
        return put(rec, 278, FixedWidth.x(ts, 26));
    }

    public static String withType(String rec, String type, int cat) {
        return put(rec, 16, FixedWidth.x(type, 2) + FixedWidth.n(cat, 4));
    }

    private static String put(String rec, int offset, String value) {
        return rec.substring(0, offset) + value + rec.substring(offset + value.length());
    }
}
