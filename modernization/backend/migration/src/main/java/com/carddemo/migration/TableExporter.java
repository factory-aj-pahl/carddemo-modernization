package com.carddemo.migration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;

/** Writes tables back in their legacy fixed-width layouts, one LF-terminated record per row, in key order. */
public class TableExporter {

    private final JdbcTemplate jdbc;

    public TableExporter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public int export(Layout layout, Path file) throws IOException {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM " + layout.table() + " ORDER BY " + layout.orderBy());
        StringBuilder sb = new StringBuilder();
        for (Map<String, Object> row : rows) {
            sb.append(RecordCodec.encode(layout, row)).append('\n');
        }
        Files.createDirectories(file.toAbsolutePath().getParent());
        Files.writeString(file, sb.toString(), StandardCharsets.ISO_8859_1);
        return rows.size();
    }

    /** Exports every base layout into {@code dir} using the legacy file names. */
    public void exportAll(Path dir) throws IOException {
        for (Layout layout : Layouts.BASE) {
            export(layout, dir.resolve(layout.fileName()));
        }
    }
}
