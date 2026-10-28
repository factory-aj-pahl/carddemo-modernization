package com.carddemo.migration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The Db2 seed files of the transaction-type module ({@code app/app-transaction-type-db2/ctl/DB2LTTYP.ctl} and
 * {@code DB2LTCAT.ctl}, run by JOB-CREADB21). They are SQL text, not fixed-width records: one
 * {@code INSERT ... SELECT ... UNION ALL} statement per table. The loader reads the literal rows of that
 * statement into {@code transaction_type} / {@code transaction_category}; the exporter writes the table back
 * as the same SQL text, byte for byte for the delivered seed (license header, column alignment, terminator).
 */
public final class Db2Seeds {

    public static final String TYPES_FILE = "DB2LTTYP.ctl";
    public static final String CATEGORIES_FILE = "DB2LTCAT.ctl";

    static final List<String> LICENSE = List.of(
        "/* Copyright Amazon.com, Inc. or its affiliates.                   */",
        "/* All Rights Reserved.                                            */",
        "/*                                                                 */",
        "/* Licensed under the Apache License, Version 2.0 (the \"License\"). */",
        "/* You may not use this file except in compliance with the License.*/",
        "/* You may obtain a copy of the License at                         */",
        "/*                                                                 */",
        "/*    http://www.apache.org/licenses/LICENSE-2.0                   */",
        "/*                                                                 */",
        "/* Unless required by applicable law or agreed to in writing,      */",
        "/* software distributed under the License is distributed on an     */",
        "/* \"AS IS\" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,    */",
        "/* either express or implied. See the License for the specific     */",
        "/* language governing permissions and limitations under the License*/");

    private static final String LIT = "'((?:[^']|'')*)'";
    private static final Pattern TYPE_ROW = Pattern.compile("^\\s*SELECT\\s+" + LIT + "\\s*,\\s*" + LIT
        + "\\s+FROM\\s+SYSIBM\\.SYSDUMMY1(?:\\s+UNION\\s+ALL)?\\s*$", Pattern.CASE_INSENSITIVE);
    private static final Pattern CATEGORY_ROW = Pattern.compile("^\\s*SELECT\\s+" + LIT + "\\s*,\\s*" + LIT + "\\s*,\\s*"
        + LIT + "\\s+FROM\\s+DMY(?:\\s+UNION\\s+ALL)?\\s*$", Pattern.CASE_INSENSITIVE);

    public record TypeRow(String typeCd, String description) {
    }

    public record CategoryRow(String typeCd, int catCd, String description) {
    }

    private Db2Seeds() {
    }

    public static List<TypeRow> parseTypes(String sql) {
        List<TypeRow> rows = new ArrayList<>();
        for (String line : sql.split("\r?\n")) {
            Matcher m = TYPE_ROW.matcher(line);
            if (m.matches()) {
                rows.add(new TypeRow(unquote(m.group(1)), unquote(m.group(2))));
            }
        }
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("no TRANSACTION_TYPE rows found");
        }
        return rows;
    }

    public static List<CategoryRow> parseCategories(String sql) {
        List<CategoryRow> rows = new ArrayList<>();
        for (String line : sql.split("\r?\n")) {
            Matcher m = CATEGORY_ROW.matcher(line);
            if (m.matches()) {
                rows.add(new CategoryRow(unquote(m.group(1)), Integer.parseInt(unquote(m.group(2)).strip()),
                    unquote(m.group(3))));
            }
        }
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("no TRANSACTION_TYPE_CATEGORY rows found");
        }
        return rows;
    }

    public static String writeTypes(List<TypeRow> rows) {
        StringBuilder sb = header();
        sb.append(" INSERT INTO CARDDEMO.TRANSACTION_TYPE\n");
        sb.append("        (TR_TYPE,TR_DESCRIPTION)\n");
        int width = rows.stream().mapToInt(r -> quote(r.description()).length()).max().orElse(0);
        for (int i = 0; i < rows.size(); i++) {
            TypeRow r = rows.get(i);
            sb.append(" SELECT ").append(quote(r.typeCd())).append(',').append(pad(quote(r.description()), width))
                .append(" FROM SYSIBM.SYSDUMMY1").append(i < rows.size() - 1 ? " UNION ALL" : "").append('\n');
        }
        sb.append(" COMMIT;\n");
        return sb.toString();
    }

    public static String writeCategories(List<CategoryRow> rows) {
        StringBuilder sb = header();
        sb.append(" INSERT INTO CARDDEMO.TRANSACTION_TYPE_CATEGORY\n");
        sb.append("      (TRC_TYPE_CODE    ,\n");
        sb.append("       TRC_TYPE_CATEGORY,\n");
        sb.append("       TRC_CAT_DATA     )\n");
        sb.append(" WITH DMY AS (SELECT * FROM SYSIBM.SYSDUMMY1)\n");
        int width = rows.stream().mapToInt(r -> quote(r.description()).length()).max().orElse(0);
        for (int i = 0; i < rows.size(); i++) {
            CategoryRow r = rows.get(i);
            sb.append(" SELECT ").append(quote(r.typeCd())).append(',').append(quote(String.format("%04d", r.catCd())))
                .append(',').append(pad(quote(r.description()), width)).append(" FROM DMY")
                .append(i < rows.size() - 1 ? " UNION ALL" : "").append('\n');
        }
        sb.append('\n').append(" COMMIT;\n");
        return sb.toString();
    }

    /**
     * Replaces the transaction types and categories with the seed rows (the module's database starts from
     * these rows, JOB-CREADB21). Either file may be null. Returns the rows loaded per file name.
     */
    public static Map<String, Integer> load(JdbcTemplate jdbc, Path typesFile, Path categoriesFile) throws IOException {
        List<TypeRow> types = typesFile == null ? null : parseTypes(read(typesFile));
        List<CategoryRow> cats = categoriesFile == null ? null : parseCategories(read(categoriesFile));
        jdbc.update("DELETE FROM transaction_category");
        Map<String, Integer> counts = new LinkedHashMap<>();
        if (types != null) {
            jdbc.update("DELETE FROM transaction_type");
            jdbc.batchUpdate("INSERT INTO transaction_type (type_cd, description) VALUES (?, ?)",
                types.stream().map(r -> new Object[] {r.typeCd(), r.description().stripTrailing()}).toList());
            counts.put(typesFile.getFileName().toString(), types.size());
        }
        if (cats != null) {
            jdbc.batchUpdate("INSERT INTO transaction_category (type_cd, cat_cd, description) VALUES (?, ?, ?)",
                cats.stream().map(r -> new Object[] {r.typeCd(), r.catCd(), r.description().stripTrailing()}).toList());
            counts.put(categoriesFile.getFileName().toString(), cats.size());
        }
        return counts;
    }

    public static int exportTypes(JdbcTemplate jdbc, Path file) throws IOException {
        List<TypeRow> rows = jdbc.query("SELECT type_cd, description FROM transaction_type ORDER BY type_cd",
            (rs, i) -> new TypeRow(rs.getString(1), rs.getString(2)));
        write(file, writeTypes(rows));
        return rows.size();
    }

    public static int exportCategories(JdbcTemplate jdbc, Path file) throws IOException {
        List<CategoryRow> rows = jdbc.query("SELECT type_cd, cat_cd, description FROM transaction_category ORDER BY type_cd, cat_cd",
            (rs, i) -> new CategoryRow(rs.getString(1), rs.getInt(2), rs.getString(3)));
        write(file, writeCategories(rows));
        return rows.size();
    }

    private static StringBuilder header() {
        StringBuilder sb = new StringBuilder();
        LICENSE.forEach(l -> sb.append(l).append('\n'));
        return sb;
    }

    private static String read(Path file) throws IOException {
        return Files.readString(file, StandardCharsets.ISO_8859_1);
    }

    private static void write(Path file, String text) throws IOException {
        Path parent = file.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(file, text, StandardCharsets.ISO_8859_1);
    }

    private static String quote(String s) {
        return "'" + s.replace("'", "''") + "'";
    }

    private static String unquote(String s) {
        return s.replace("''", "'");
    }

    private static String pad(String s, int width) {
        return s.length() >= width ? s : s + " ".repeat(width - s.length());
    }
}
