package com.carddemo.dataexchange.internal;

import com.carddemo.common.batch.JobLog;
import com.carddemo.common.codec.FixedWidth;
import com.carddemo.dataexchange.api.JobResult;
import com.carddemo.migration.Layout;
import com.carddemo.migration.Layouts;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * CBEXPORT: the five masters, in the fixed section order, into one file of 500-byte CVEXPORT records with
 * a continuous binary sequence number (BR-CBEXPORT-01..09). The file is written mode 600 and nothing
 * from it goes to SYSOUT (Q-CBEXPORT-01). Close failures are not checked (ERR-CBEXPORT-05); every record
 * is on disk when its write returns. An abend leaves the records written so far, as the KSDS does.
 */
final class ExportJob {

    private static final String ABENDING = "CBEXPORT: ABENDING PROGRAM";
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("uuuu-MM-dd");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    interface Encoder {
        byte[] encode(String record, String timestamp, long sequence);
    }

    record Section(String input, Layout layout, String processing, String exported, Encoder encoder) {
    }

    static final List<Section> SECTIONS = List.of(
        new Section("CUSTOMER-INPUT", Layouts.CUSTDATA, "customer", "Customers exported: ", ExportCodec::customer),
        new Section("ACCOUNT-INPUT", Layouts.ACCTDATA, "account", "Accounts exported: ", ExportCodec::account),
        new Section("XREF-INPUT", Layouts.CARDXREF, "cross-reference", "Cross-references exported: ", ExportCodec::xref),
        new Section("TRANSACTION-INPUT", Layouts.TRANSACT, "transaction", "Transactions exported: ", ExportCodec::transaction),
        new Section("CARD-INPUT", Layouts.CARDDATA, "card", "Cards exported: ", ExportCodec::card));

    private static final String[] TOTAL_LABELS = {"Customers Exported: ", "Accounts Exported: ", "XRefs Exported: ",
        "Transactions Exported: ", "Cards Exported: "};

    private final JobLog log;
    private final Sinks sinks;
    private final Clock clock;

    ExportJob(JobLog log, Sinks sinks, Clock clock) {
        this.log = log;
        this.sinks = sinks;
        this.clock = clock;
    }

    JobResult run(Function<Layout, RecordSource> tables, Path exportFile) {
        log.display("CBEXPORT: Starting Customer Data Export");
        LocalDateTime now = LocalDateTime.now(clock);
        String date = now.format(DATE);
        String time = now.format(TIME);
        String timestamp = date + " " + time + ".00";
        List<RecordSource> inputs = new ArrayList<>();
        for (Section s : SECTIONS) {
            RecordSource src = tables.apply(s.layout());
            try {
                src.open();
            } catch (FileStatusException e) {
                return abend("ERROR: Cannot open " + s.input() + ", Status: " + e.status());
            }
            inputs.add(src);
        }
        RecordSink out;
        try {
            out = sinks.create(exportFile);
        } catch (IOException e) {
            return abend("ERROR: Cannot open EXPORT-OUTPUT, Status: " + FileStatuses.openOutput(e));
        }
        log.display("CBEXPORT: Export Date: " + date);
        log.display("CBEXPORT: Export Time: " + time);
        long sequence = 0;
        long[] counts = new long[SECTIONS.size()];
        for (int i = 0; i < SECTIONS.size(); i++) {
            Section s = SECTIONS.get(i);
            RecordSource src = inputs.get(i);
            log.display("CBEXPORT: Processing " + s.processing() + " records");
            while (true) {
                String rec;
                try {
                    rec = src.read();
                } catch (FileStatusException e) {
                    return abend("ERROR: Reading " + s.input() + ", Status: " + e.status());
                }
                if (rec == null) {
                    break;
                }
                sequence++;
                try {
                    out.write(s.encoder().encode(rec, timestamp, sequence));
                } catch (IOException e) {
                    return abend("ERROR: Writing export record, Status: " + FileStatuses.write(e));
                }
                counts[i]++;
            }
            log.display("CBEXPORT: " + s.exported() + FixedWidth.n(counts[i], 9));
        }
        for (RecordSource src : inputs) {
            try {
                src.close();
            } catch (FileStatusException ignored) {
                // ERR-CBEXPORT-05: CLOSE statuses are not checked
            }
        }
        try {
            out.close();
        } catch (IOException ignored) {
            // ERR-CBEXPORT-05
        }
        log.display("CBEXPORT: Export completed");
        long total = 0;
        for (int i = 0; i < counts.length; i++) {
            log.display("CBEXPORT: " + TOTAL_LABELS[i] + FixedWidth.n(counts[i], 9));
            total += counts[i];
        }
        log.display("CBEXPORT: Total Records Exported: " + FixedWidth.n(total, 9));
        return JobResult.completed();
    }

    private JobResult abend(String message) {
        log.display(message);
        log.display(ABENDING);
        return JobResult.abend();
    }
}
