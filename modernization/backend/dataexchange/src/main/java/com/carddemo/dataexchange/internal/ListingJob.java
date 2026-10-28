package com.carddemo.dataexchange.internal;

import com.carddemo.common.batch.JobLog;
import com.carddemo.dataexchange.api.JobResult;
import com.carddemo.migration.Layout;
import com.carddemo.migration.Layouts;
import java.util.function.UnaryOperator;

/**
 * The listing utilities CBACT02C, CBACT03C, and CBCUS01C: every record of one master in key order, one
 * DISPLAY line each, framed by start and end messages. Each record is shown once (Q-CBACT03C-01,
 * Q-CBCUS01C-01) and masked at the byte positions of the reference patches (B-MASK, Q-READCARD-01).
 */
final class ListingJob {

    record Spec(String program, Layout layout, String openError, String readError, String closeError,
            UnaryOperator<String> mask) {
    }

    /** RP-CBACT02C-1: card number bytes 1-12 and CVV bytes 28-30 masked. */
    static final Spec CARDS = new Spec("CBACT02C", Layouts.CARDDATA, "ERROR OPENING CARDFILE", "ERROR READING CARDFILE",
        "ERROR CLOSING CARDFILE", r -> "*".repeat(12) + r.substring(12, 27) + "***" + r.substring(30));

    /** RP-CBACT03C-2: card number bytes 1-12 masked. */
    static final Spec XREFS = new Spec("CBACT03C", Layouts.CARDXREF, "ERROR OPENING XREFFILE", "ERROR READING XREFFILE",
        "ERROR CLOSING XREFFILE", r -> "*".repeat(12) + r.substring(12));

    /**
     * RP-CBCUS01C-2: SSN bytes 280-284, government ID 289-308, date of birth 309-318, and EFT account
     * 319-324 masked; names, addresses, and phones stay (Q-CBCUS01C-02).
     */
    static final Spec CUSTOMERS = new Spec("CBCUS01C", Layouts.CUSTDATA, "ERROR OPENING CUSTFILE",
        "ERROR READING CUSTOMER FILE", "ERROR CLOSING CUSTOMER FILE",
        r -> r.substring(0, 279) + "*****" + r.substring(284, 288) + "*".repeat(30) + "******" + r.substring(324));

    private final JobLog log;

    ListingJob(JobLog log) {
        this.log = log;
    }

    JobResult run(Spec spec, RecordSource file) {
        log.display("START OF EXECUTION OF PROGRAM " + spec.program());
        try {
            file.open();
        } catch (FileStatusException e) {
            return abend(spec.openError(), e);
        }
        while (true) {
            String rec;
            try {
                rec = file.read();
            } catch (FileStatusException e) {
                return abend(spec.readError(), e);
            }
            if (rec == null) {
                break;
            }
            log.display(spec.mask().apply(rec));
        }
        try {
            file.close();
        } catch (FileStatusException e) {
            return abend(spec.closeError(), e);
        }
        log.display("END OF EXECUTION OF PROGRAM " + spec.program());
        return JobResult.completed();
    }

    private JobResult abend(String message, FileStatusException e) {
        log.display(message);
        log.display(FileStatuses.statusLine(e.status()));
        log.display("ABENDING PROGRAM");
        return JobResult.abend();
    }
}
