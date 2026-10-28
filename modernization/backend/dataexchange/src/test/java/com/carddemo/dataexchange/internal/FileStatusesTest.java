package com.carddemo.dataexchange.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.carddemo.test.Covers;
import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.NoSuchFileException;
import org.junit.jupiter.api.Test;

class FileStatusesTest {

    @Test
    @Covers({"ERR-CBACT02C-04", "ERR-CBACT03C-04", "ERR-CBCUS01C-04", "ERR-CBACT01C-05"})
    void status_line_shows_00_plus_a_numeric_status_and_the_ebcdic_byte_value_for_9x_and_non_numeric() {
        assertThat(FileStatuses.statusLine("35")).isEqualTo("FILE STATUS IS: NNNN0035");
        assertThat(FileStatuses.statusLine("30")).isEqualTo("FILE STATUS IS: NNNN0030");
        assertThat(FileStatuses.statusLine("93")).isEqualTo("FILE STATUS IS: NNNN9243");
        assertThat(FileStatuses.statusLine("9A")).isEqualTo("FILE STATUS IS: NNNN9193");
        assertThat(FileStatuses.statusLine("0A")).isEqualTo("FILE STATUS IS: NNNN0193");
    }

    @Test
    void target_failures_map_to_the_nearest_file_status() {
        assertThat(FileStatuses.openInput(new NoSuchFileException("x"))).isEqualTo("35");
        assertThat(FileStatuses.openInput(new AccessDeniedException("x"))).isEqualTo("37");
        assertThat(FileStatuses.openInput(new IOException("x"))).isEqualTo("30");
        assertThat(FileStatuses.openOutput(new IOException("x"))).isEqualTo("37");
        assertThat(FileStatuses.write(new IOException("No space left on device"))).isEqualTo("34");
        assertThat(FileStatuses.write(new IOException("Input/output error"))).isEqualTo("30");
    }
}
