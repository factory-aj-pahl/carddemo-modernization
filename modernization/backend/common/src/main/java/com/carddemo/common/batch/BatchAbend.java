package com.carddemo.common.batch;

import java.util.ArrayList;
import java.util.List;

/**
 * A COBOL abend path (9999-ABEND-PROGRAM, B-ABEND): the job stops, the current unit of work rolls back,
 * and the program's error message and file-status line are displayed before {@code ABENDING PROGRAM}.
 */
public class BatchAbend extends RuntimeException {

    private final String fileStatus;

    /**
     * @param message    the COBOL error DISPLAY text
     * @param fileStatus the two-character VSAM file status the COBOL would show, or null when the target
     *                   failure has no file-status equivalent (then no status line is displayed)
     */
    public BatchAbend(String message, String fileStatus) {
        super(message);
        this.fileStatus = fileStatus;
    }

    public BatchAbend(String message, String fileStatus, Throwable cause) {
        super(message, cause);
        this.fileStatus = fileStatus;
    }

    /** The lines displayed before {@code ABENDING PROGRAM}. */
    public List<String> sysoutLines() {
        List<String> lines = new ArrayList<>();
        lines.add(getMessage());
        if (fileStatus != null) {
            lines.add(statusLine(fileStatus));
        }
        return lines;
    }

    /**
     * 9910-DISPLAY-IO-STATUS for a numeric status that does not start with 9: {@code FILE STATUS IS: NNNN}
     * followed by {@code 00} and the status.
     */
    public static String statusLine(String status) {
        return "FILE STATUS IS: NNNN00" + status;
    }
}
