package com.carddemo.dataexchange.api;

/** Outcome of a data exchange job: completed (return code 0) or abended (B-ABEND). */
public record JobResult(boolean abended) {

    public static JobResult completed() {
        return new JobResult(false);
    }

    public static JobResult abend() {
        return new JobResult(true);
    }
}
