package com.carddemo.common.batch;

import java.util.List;

/**
 * A B-ABEND for which the COBOL has no DISPLAY text (for example a value that no longer fits its picture,
 * Q-CBTRN03C-10, Q-CBSTM03A-09): SYSOUT shows only {@code ABENDING PROGRAM}; the reason goes to the
 * error stream and the application log.
 */
public class UnlabeledAbend extends BatchAbend {

    public UnlabeledAbend(String reason, Throwable cause) {
        super(reason, null, cause);
    }

    @Override
    public List<String> sysoutLines() {
        return List.of();
    }
}
