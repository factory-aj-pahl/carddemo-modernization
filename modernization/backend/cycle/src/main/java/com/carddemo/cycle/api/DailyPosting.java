package com.carddemo.cycle.api;

import java.time.LocalDate;
import java.util.List;

/** POSTTRAN (CBTRN02C) posting of daily transaction records, one database transaction per record (B-UOW). */
public interface DailyPosting {

    /**
     * Validates and posts one 350-byte DALYTRAN record, or stores it as a reject of the run. Must run inside
     * the caller's transaction, which is the record's unit of work. Throws {@code BatchAbend} on the COBOL
     * abend paths.
     */
    PostingOutcome post(String dailyRecord, long runId, LocalDate businessDate);

    /** The 430-byte reject records of a run (daily record + reason + description), in the order rejected. */
    List<String> rejectRecords(long runId);
}
