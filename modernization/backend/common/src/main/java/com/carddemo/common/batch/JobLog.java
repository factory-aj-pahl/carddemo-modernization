package com.carddemo.common.batch;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * SYSOUT of a batch job: the DISPLAY lines of the COBOL program, written verbatim to standard output
 * (application logging never goes there). Tests capture the lines of a run.
 */
@Component
public class JobLog {

    private final PrintStream out = System.out;
    private final List<String> captured = new ArrayList<>();
    private boolean capturing;

    public synchronized void display(String line) {
        out.println(line);
        out.flush();
        if (capturing) {
            captured.add(line);
        }
    }

    public synchronized void startCapture() {
        captured.clear();
        capturing = true;
    }

    public synchronized List<String> captured() {
        return List.copyOf(captured);
    }
}
