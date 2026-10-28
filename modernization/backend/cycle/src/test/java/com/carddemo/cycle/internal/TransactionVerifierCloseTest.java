package com.carddemo.cycle.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.carddemo.common.batch.BatchAbend;
import com.carddemo.common.batch.JobLog;
import com.carddemo.test.Covers;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import org.junit.jupiter.api.Test;

/** 9000-DALYTRAN-CLOSE of CBTRN01C (ERR-CBTRN01C-03, Q-CBTRN01C-02); a regular file cannot be made to fail on close. */
class TransactionVerifierCloseTest {

    @Test
    @Covers({"ERR-CBTRN01C-03", "Q-CBTRN01C-02"})
    void a_daily_file_close_failure_keeps_the_cobol_text_without_another_files_status() {
        JobLog log = new JobLog();
        log.startCapture();
        TransactionVerifier verifier = new TransactionVerifier(null, null, log);
        BufferedReader daily = new BufferedReader(new StringReader("")) {
            @Override
            public void close() throws IOException {
                throw new IOException("injected close failure");
            }
        };
        assertThatThrownBy(() -> verifier.verify(daily)).isInstanceOfSatisfying(BatchAbend.class,
            a -> assertThat(a.sysoutLines()).containsExactly("ERROR CLOSING CUSTOMER FILE"));
        assertThat(log.captured()).doesNotContain("END OF EXECUTION OF PROGRAM CBTRN01C");
    }
}
