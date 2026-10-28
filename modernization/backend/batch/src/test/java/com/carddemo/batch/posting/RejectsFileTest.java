package com.carddemo.batch.posting;

import static org.assertj.core.api.Assertions.assertThat;

import com.carddemo.test.Covers;
import java.io.FilterWriter;
import java.io.IOException;
import java.io.StringWriter;
import java.io.Writer;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Writing the committed rejects to DALYREJS (2500-WRITE-REJECT-REC, 9300-DALYREJS-CLOSE). */
class RejectsFileTest {

    @Test
    void all_rejects_are_written_one_per_line() {
        StringWriter out = new StringWriter();
        assertThat(PosttranStep.writeRejects(out, List.of("r1", "r2"))).isNull();
        assertThat(out.toString()).isEqualTo("r1\nr2\n");
    }

    @Test
    @Covers({"ERR-CBTRN02C-03"})
    void a_write_failure_is_reported_as_the_reject_write() {
        Writer failing = new Writer() {
            @Override
            public void write(char[] buf, int off, int len) throws IOException {
                throw new IOException("injected write failure");
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        assertThat(PosttranStep.writeRejects(failing, List.of("r1"))).isEqualTo("ERROR WRITING TO REJECTS FILE");
    }

    @Test
    @Covers({"ERR-CBTRN02C-08", "Q-CBTRN02C-05"})
    void a_close_failure_names_the_rejects_file_without_another_files_status() {
        StringWriter sink = new StringWriter();
        Writer failingClose = new FilterWriter(sink) {
            @Override
            public void close() throws IOException {
                throw new IOException("injected close failure");
            }
        };
        assertThat(PosttranStep.writeRejects(failingClose, List.of("r1"))).isEqualTo("ERROR CLOSING DAILY REJECTS FILE");
        assertThat(sink.toString()).isEqualTo("r1\n");
    }
}
