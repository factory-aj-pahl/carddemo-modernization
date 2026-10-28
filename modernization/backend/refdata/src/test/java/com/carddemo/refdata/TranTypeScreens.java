package com.carddemo.refdata;

import com.carddemo.migration.Db2Seeds;
import com.carddemo.test.RepoPaths;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;

/** Fixtures of the transaction-type screen tests: the JOB-CREADB21 seed and the screen as the web app sends it back. */
final class TranTypeScreens {

    static final Path DB2_CTL = RepoPaths.root().resolve("app/app-transaction-type-db2/ctl");

    private TranTypeScreens() {
    }

    /** The seven Db2 seed types and their 18 categories (spec test assumptions). */
    static void loadDb2Seed(JdbcTemplate jdbc) {
        try {
            Db2Seeds.load(jdbc, DB2_CTL.resolve(Db2Seeds.TYPES_FILE), DB2_CTL.resolve(Db2Seeds.CATEGORIES_FILE));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static String description(JdbcTemplate jdbc, String code) {
        List<String> d = jdbc.queryForList("SELECT description FROM transaction_type WHERE type_cd = ?", String.class, code);
        return d.isEmpty() ? null : d.get(0);
    }

    /** The COTRTLIC screen as shown, returned unchanged (filters, select codes, descriptions). */
    static Map<String, Object> listInput(JsonNode screen) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("typeFilter", screen.at("/fields/typeFilter").asText());
        m.put("descFilter", screen.at("/fields/descFilter").asText());
        List<Map<String, String>> rows = new ArrayList<>();
        for (JsonNode r : screen.at("/fields/rows")) {
            Map<String, String> row = new LinkedHashMap<>();
            row.put("select", r.get("select").asText());
            row.put("description", r.get("description").asText());
            rows.add(row);
        }
        m.put("rows", rows);
        return m;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> select(Map<String, Object> input, int row, String code) {
        ((List<Map<String, String>>) input.get("rows")).get(row - 1).put("select", code);
        return input;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> describe(Map<String, Object> input, int row, String text) {
        ((List<Map<String, String>>) input.get("rows")).get(row - 1).put("description", text);
        return input;
    }

    static List<String> types(JsonNode screen) {
        List<String> t = new ArrayList<>();
        for (JsonNode r : screen.at("/fields/rows")) {
            if (!r.get("type").asText().isEmpty()) {
                t.add(r.get("type").asText());
            }
        }
        return t;
    }

    static JsonNode row(JsonNode screen, int row) {
        return screen.at("/fields/rows/" + (row - 1));
    }
}
