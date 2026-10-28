package com.carddemo.dataexchange.internal;

import com.carddemo.migration.Layout;
import com.carddemo.migration.RecordCodec;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Tables read through the migration layouts, so each row comes back byte for byte as the record it was
 * loaded from (data-roundtrip). Records are sorted by their bytes, which is the GnuCOBOL KSDS key order
 * (the key is the record prefix for every base layout); the database collation is not used.
 */
final class JdbcTables implements Tables {

    private final JdbcTemplate jdbc;

    JdbcTables(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public RecordSource of(Layout layout) {
        return new RecordSource() {
            private Iterator<String> records;

            @Override
            public void open() {
                List<Map<String, Object>> rows;
                try {
                    rows = jdbc.queryForList("SELECT * FROM " + layout.table());
                } catch (DataAccessException e) {
                    throw new FileStatusException(FileStatuses.table(e), e);
                }
                records = rows.stream().map(r -> RecordCodec.encode(layout, r)).sorted().iterator();
            }

            @Override
            public String read() {
                return records.hasNext() ? records.next() : null;
            }

            @Override
            public void close() {
                records = null;
            }
        };
    }
}
