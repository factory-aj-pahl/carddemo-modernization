package com.carddemo.reporting.internal;

import java.time.LocalDate;
import java.util.Optional;

/** Starts the transaction report job (TRANREPT) and reports its state; the target of CORPT00C's job submission. */
public interface ReportLauncher {

    /** Parameters of one report run (Q-CORPT00C-07): the date range and the requester. */
    record Parameters(LocalDate startDate, LocalDate endDate, String userId) {
    }

    /** Nothing was started (Q-CORPT00C-09). */
    final class LaunchFailed extends Exception {
        public LaunchFailed(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** Starts one run with the given ID, all or nothing. */
    void launch(String runId, Parameters parameters) throws LaunchFailed;

    /** The scheduler's state of a run (for example {@code queued}, {@code running}, {@code success}); empty when unknown. */
    Optional<String> state(String runId);
}
