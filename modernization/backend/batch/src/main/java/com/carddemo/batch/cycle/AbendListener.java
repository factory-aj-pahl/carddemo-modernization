package com.carddemo.batch.cycle;

import com.carddemo.common.batch.BatchAbend;
import com.carddemo.common.batch.JobLog;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.StepExecutionListener;

/**
 * 9999-ABEND-PROGRAM for a failed step (B-ABEND): the abend's DISPLAY lines, then {@code ABENDING PROGRAM}.
 * The exception itself goes to the error stream through the command line.
 */
class AbendListener implements StepExecutionListener {

    private final JobLog log;

    AbendListener(JobLog log) {
        this.log = log;
    }

    @Override
    public ExitStatus afterStep(StepExecution se) {
        if (se.getStatus() == BatchStatus.COMPLETED) {
            return se.getExitStatus();
        }
        report(se, log);
        return ExitStatus.FAILED;
    }

    static void report(StepExecution se, JobLog log) {
        for (Throwable t : se.getFailureExceptions()) {
            BatchAbend abend = abendOf(t);
            if (abend != null) {
                abend.sysoutLines().forEach(log::display);
            }
        }
        log.display("ABENDING PROGRAM");
    }

    static BatchAbend abendOf(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c instanceof BatchAbend a) {
                return a;
            }
        }
        return null;
    }
}
