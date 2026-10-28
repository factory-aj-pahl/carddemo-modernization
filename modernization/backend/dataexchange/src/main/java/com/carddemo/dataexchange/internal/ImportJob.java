package com.carddemo.dataexchange.internal;

import static java.nio.charset.StandardCharsets.ISO_8859_1;

import com.carddemo.common.batch.JobLog;
import com.carddemo.common.codec.FixedWidth;
import com.carddemo.dataexchange.api.ImportFiles;
import com.carddemo.dataexchange.api.JobResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;

/**
 * CBIMPORT: reads an export file in sequence-number order and writes each record, by type, to its legacy
 * record file (BR-CBIMPORT-01..10). Fixes: FILLER bytes are spaces (Q-CBIMPORT-03), the error line holds
 * the 9-digit sequence (Q-CBIMPORT-04), an error-file write failure abends (Q-CBIMPORT-05), and a record
 * whose numeric field cannot be decoded goes to the error file with {@code INVALID NUMERIC DATA}
 * (Q-CBIMPORT-07). Outputs are LF-terminated fixed-length records, replaced on rerun (Q-CBIMPORT-08),
 * mode 600 (Q-CBIMPORT-10), and deleted when the job abends (DISP=(NEW,CATLG,DELETE)).
 */
final class ImportJob {

    private static final String ABENDING = "CBIMPORT: ABENDING PROGRAM";
    private static final DateTimeFormatter CURRENT_DATE = DateTimeFormatter.ofPattern("uuuuMMddHHmmss");
    private static final DateTimeFormatter OFFSET = DateTimeFormatter.ofPattern("xx");
    static final int ERROR_LENGTH = 132;

    private record Output(String name, String writeError, Function<ImportFiles, Path> path) {
    }

    private static final List<Output> OUTPUTS = List.of(
        new Output("CUSTOMER-OUTPUT", "customer record", ImportFiles::customers),
        new Output("ACCOUNT-OUTPUT", "account record", ImportFiles::accounts),
        new Output("XREF-OUTPUT", "xref record", ImportFiles::xrefs),
        new Output("TRANSACTION-OUTPUT", "transaction record", ImportFiles::transactions),
        new Output("CARD-OUTPUT", "card record", ImportFiles::cards),
        new Output("ERROR-OUTPUT", "error record", ImportFiles::errors));

    private static final int CUST = 0;
    private static final int ACCT = 1;
    private static final int XREF = 2;
    private static final int TRAN = 3;
    private static final int CARD = 4;
    private static final int ERRS = 5;

    private final JobLog log;
    private final Sinks sinks;
    private final Clock clock;

    ImportJob(JobLog log, Sinks sinks, Clock clock) {
        this.log = log;
        this.sinks = sinks;
        this.clock = clock;
    }

    private static final class Abend extends RuntimeException {
        Abend() {
            super(null, null, false, false);
        }
    }

    JobResult run(Path exportFile, ImportFiles files) {
        List<RecordSink> open = new ArrayList<>();
        List<Path> created = new ArrayList<>();
        try {
            log.display("CBIMPORT: Starting Customer Data Import");
            String now = currentDate();
            String date = now.substring(0, 4) + "-" + now.substring(4, 6) + "-" + now.substring(6, 8);
            String time = now.substring(8, 10) + ":" + now.substring(10, 12) + ":" + now.substring(12, 14);
            List<byte[]> records = readExport(exportFile);
            boolean truncated = records.remove(null);
            RecordSink[] out = new RecordSink[OUTPUTS.size()];
            for (int i = 0; i < OUTPUTS.size(); i++) {
                Output o = OUTPUTS.get(i);
                Path p = o.path().apply(files);
                try {
                    out[i] = sinks.create(p);
                } catch (IOException e) {
                    abend("ERROR: Cannot open " + o.name() + ", Status: " + FileStatuses.openOutput(e));
                }
                created.add(p);
                open.add(out[i]);
            }
            log.display("CBIMPORT: Import Date: " + date);
            log.display("CBIMPORT: Import Time: " + time);
            long read = 0;
            long unknown = 0;
            long errors = 0;
            long[] imported = new long[5];
            for (byte[] e : records) {
                read++;
                int target = switch ((char) (e[0] & 0xFF)) {
                    case 'C' -> CUST;
                    case 'A' -> ACCT;
                    case 'X' -> XREF;
                    case 'T' -> TRAN;
                    case 'D' -> CARD;
                    default -> -1;
                };
                if (target < 0) {
                    unknown++;
                    writeError(out[ERRS], e, "Unknown record type encountered");
                    errors++;
                    continue;
                }
                String rec;
                try {
                    rec = switch (target) {
                        case CUST -> ExportCodec.toCustomer(e);
                        case ACCT -> ExportCodec.toAccount(e);
                        case XREF -> ExportCodec.toXref(e);
                        case TRAN -> ExportCodec.toTransaction(e);
                        default -> ExportCodec.toCard(e);
                    };
                } catch (NumberFormatException | ArithmeticException bad) {
                    writeError(out[ERRS], e, "INVALID NUMERIC DATA");
                    errors++;
                    continue;
                }
                write(out[target], rec, OUTPUTS.get(target).writeError());
                imported[target]++;
            }
            if (truncated) {
                abend("ERROR: Reading EXPORT-INPUT, Status: 04");
            }
            log.display("CBIMPORT: Import validation completed");
            log.display("CBIMPORT: No validation errors detected");
            for (RecordSink s : open) {
                try {
                    s.close();
                } catch (IOException ignored) {
                    // ERR-CBIMPORT-05: CLOSE statuses are not checked; each write is already on disk
                }
            }
            open.clear();
            log.display("CBIMPORT: Import completed");
            log.display("CBIMPORT: Total Records Read: " + FixedWidth.n(read, 9));
            log.display("CBIMPORT: Customers Imported: " + FixedWidth.n(imported[CUST], 9));
            log.display("CBIMPORT: Accounts Imported: " + FixedWidth.n(imported[ACCT], 9));
            log.display("CBIMPORT: XRefs Imported: " + FixedWidth.n(imported[XREF], 9));
            log.display("CBIMPORT: Transactions Imported: " + FixedWidth.n(imported[TRAN], 9));
            log.display("CBIMPORT: Cards Imported: " + FixedWidth.n(imported[CARD], 9));
            log.display("CBIMPORT: Errors Written: " + FixedWidth.n(errors, 9));
            log.display("CBIMPORT: Unknown Record Types: " + FixedWidth.n(unknown, 9));
            return JobResult.completed();
        } catch (Abend a) {
            discard(open, created);
            return JobResult.abend();
        }
    }

    /** FUNCTION CURRENT-DATE: {@code YYYYMMDDhhmmsshh+hhmm} (21 characters), local time. */
    String currentDate() {
        ZonedDateTime now = ZonedDateTime.now(clock);
        return now.format(CURRENT_DATE) + FixedWidth.n(now.getNano() / 10_000_000, 2) + now.format(OFFSET);
    }

    /**
     * The export KSDS in key order: records sorted by their unsigned binary sequence number. A trailing
     * partial record (a damaged file) is returned as a final null, read after every complete record,
     * where the record-length-conflict status {@code 04} takes ERR-CBIMPORT-02.
     */
    private List<byte[]> readExport(Path file) {
        byte[] data;
        try {
            data = Files.readAllBytes(file);
        } catch (IOException e) {
            abend("ERROR: Cannot open EXPORT-INPUT, Status: " + FileStatuses.openInput(e));
            return List.of();
        }
        List<byte[]> records = new ArrayList<>();
        int complete = data.length - data.length % ExportCodec.LENGTH;
        for (int off = 0; off < complete; off += ExportCodec.LENGTH) {
            records.add(Arrays.copyOfRange(data, off, off + ExportCodec.LENGTH));
        }
        records.sort(Comparator.comparingLong(ExportCodec::sequence));
        if (complete < data.length) {
            records.add(null);
        }
        return records;
    }

    /** BR-CBIMPORT-07 error line: timestamp X(26), type, 9-digit sequence (RP-CBIMPORT-2), message X(50). */
    private void writeError(RecordSink errout, byte[] e, String message) {
        String line = FixedWidth.x(currentDate(), 26) + "|" + ExportCodec.str(e, 0, 1) + "|"
            + FixedWidth.n(ExportCodec.sequence(e), 9) + "|" + FixedWidth.x(message, 50);
        write(errout, FixedWidth.x(line, ERROR_LENGTH), "error record");
    }

    private void write(RecordSink sink, String rec, String what) {
        try {
            sink.write((rec + "\n").getBytes(ISO_8859_1));
        } catch (IOException ex) {
            abend("ERROR: Writing " + what + ", Status: " + FileStatuses.write(ex));
        }
    }

    private void abend(String message) {
        log.display(message);
        log.display(ABENDING);
        throw new Abend();
    }

    private static void discard(List<RecordSink> open, List<Path> created) {
        for (RecordSink s : open) {
            try {
                s.close();
            } catch (IOException ignored) {
                // deleted next
            }
        }
        for (Path p : created) {
            try {
                Files.deleteIfExists(p);
            } catch (IOException ex) {
                System.err.println("could not delete " + p + ": " + ex);
            }
        }
    }
}
