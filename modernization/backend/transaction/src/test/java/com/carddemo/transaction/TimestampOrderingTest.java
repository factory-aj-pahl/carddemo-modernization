package com.carddemo.transaction;

import static org.assertj.core.api.Assertions.assertThat;

import com.carddemo.common.codec.LegacyTimestamps;
import com.carddemo.test.Covers;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Q-ENT-08: every legacy timestamp text parses, the parsed values sort chronologically, and the text is unchanged. */
class TimestampOrderingTest {

    static final String BATCH = "2022-06-10-09.00.00.120000";
    static final String ONLINE = "2022-06-10 10:00:00.000000";
    static final String DATE_ONLY = "2022-06-10";
    static final String FEED = "2022-06-09 23:59:59.999999";

    @Test
    @Covers({"Q-ENT-08"})
    void mixed_writer_formats_sort_by_parsed_value_not_by_text() {
        List<String> texts = List.of(ONLINE, BATCH, DATE_ONLY, FEED);
        assertThat(texts.stream().sorted().toList()).as("text order mixes writers")
            .containsExactly(FEED, DATE_ONLY, ONLINE, BATCH);
        assertThat(texts.stream().sorted(Comparator.comparing(LegacyTimestamps::parse)).toList())
            .containsExactly(FEED, DATE_ONLY, BATCH, ONLINE);
        assertThat(LegacyTimestamps.parse(BATCH)).isEqualTo(LocalDateTime.of(2022, 6, 10, 9, 0, 0, 120_000_000));
        assertThat(LegacyTimestamps.parse("                          ")).isNull();
        assertThat(LegacyTimestamps.parse("garbage")).isNull();
    }

    @Test
    @Covers({"Q-ENT-08"})
    void the_rendered_text_is_what_the_cobol_writer_produced() {
        assertThat(LegacyTimestamps.db2(LegacyTimestamps.parse(BATCH))).isEqualTo(BATCH);
        assertThat(LegacyTimestamps.cicsTimestamp(LegacyTimestamps.parse(ONLINE))).isEqualTo(ONLINE);
    }
}
