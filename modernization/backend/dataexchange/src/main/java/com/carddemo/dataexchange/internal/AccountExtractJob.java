package com.carddemo.dataexchange.internal;

import static java.nio.charset.StandardCharsets.ISO_8859_1;

import com.carddemo.common.batch.JobLog;
import com.carddemo.common.codec.Overpunch;
import com.carddemo.dataexchange.api.AccountExtractFiles;
import com.carddemo.dataexchange.api.JobResult;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.List;

/**
 * CBACT01C (JOB-READACCT): lists each account field by field and writes the three demonstration extracts
 * (BR-CBACT01C-01..07). Fixes: a non-zero cycle debit is written as itself (Q-CBACT01C-01), the reissue
 * date is {@code YYYYMMDD} + 2 spaces (Q-CBACT01C-02), only a successful write counts as success
 * (Q-CBACT01C-04), outputs are closed at the end (Q-CBACT01C-06). COBDATFT is replaced by a strict date
 * conversion; an invalid reissue date abends (Q-CBACT01C-08). On an abend the outputs are deleted, as
 * DISP=(NEW,CATLG,DELETE) does.
 */
final class AccountExtractJob {

    static final int OUT_LENGTH = 107;
    static final int ARRAY_LENGTH = 110;
    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("uuuu-MM-dd").withResolverStyle(ResolverStyle.STRICT);
    private static final BigInteger DEFAULT_DEBIT = BigInteger.valueOf(252500);
    private static final String ABENDING = "ABENDING PROGRAM";

    private final JobLog log;
    private final Sinks sinks;

    AccountExtractJob(JobLog log, Sinks sinks) {
        this.log = log;
        this.sinks = sinks;
    }

    /** Thrown inside the run when an abend path has displayed its messages. */
    private static final class Abend extends RuntimeException {
        Abend() {
            super(null, null, false, false);
        }
    }

    JobResult run(RecordSource accounts, AccountExtractFiles files) {
        List<Path> created = new ArrayList<>();
        List<RecordSink> open = new ArrayList<>();
        try {
            log.display("START OF EXECUTION OF PROGRAM CBACT01C");
            try {
                accounts.open();
            } catch (FileStatusException e) {
                abend("ERROR OPENING ACCTFILE", e.status());
            }
            RecordSink out = openOutput(files.outFile(), "ERROR OPENING OUTFILE", created, open);
            RecordSink arr = openOutput(files.arrayFile(), "ERROR OPENING ARRAYFILE", created, open);
            RecordSink vbrc = openOutput(files.vbrcFile(), "ERROR OPENING VBRC FILE", created, open);
            while (true) {
                String rec;
                try {
                    rec = accounts.read();
                } catch (FileStatusException e) {
                    abend("ERROR READING ACCOUNT FILE", e.status());
                    return JobResult.abend();
                }
                if (rec == null) {
                    break;
                }
                display(rec);
                write(out, (outRecord(rec)).getBytes(ISO_8859_1));
                write(arr, arrayRecord(rec));
                String vb1 = rec.substring(0, 12);
                String vb2 = rec.substring(0, 11) + rec.substring(12, 36) + rec.substring(68, 72);
                log.display("VBRC-REC1:" + vb1);
                log.display("VBRC-REC2:" + vb2);
                write(vbrc, variable(vb1));
                write(vbrc, variable(vb2));
                log.display(rec);
            }
            try {
                accounts.close();
            } catch (FileStatusException e) {
                abend("ERROR CLOSING ACCOUNT FILE", e.status());
            }
            for (RecordSink s : open) {
                try {
                    s.close();
                } catch (IOException e) {
                    abend("ACCOUNT FILE WRITE STATUS IS:" + FileStatuses.write(e), FileStatuses.write(e));
                }
            }
            open.clear();
            log.display("END OF EXECUTION OF PROGRAM CBACT01C");
            return JobResult.completed();
        } catch (Abend e) {
            discard(open, created);
            return JobResult.abend();
        }
    }

    private void display(String r) {
        log.display("ACCT-ID                 :" + r.substring(0, 11));
        log.display("ACCT-ACTIVE-STATUS      :" + r.substring(11, 12));
        log.display("ACCT-CURR-BAL           :" + r.substring(12, 24));
        log.display("ACCT-CREDIT-LIMIT       :" + r.substring(24, 36));
        log.display("ACCT-CASH-CREDIT-LIMIT  :" + r.substring(36, 48));
        log.display("ACCT-OPEN-DATE          :" + r.substring(48, 58));
        log.display("ACCT-EXPIRAION-DATE     :" + r.substring(58, 68));
        log.display("ACCT-REISSUE-DATE       :" + r.substring(68, 78));
        log.display("ACCT-CURR-CYC-CREDIT    :" + r.substring(78, 90));
        log.display("ACCT-CURR-CYC-DEBIT     :" + r.substring(90, 102));
        log.display("ACCT-GROUP-ID           :" + r.substring(112, 122));
        log.display("-------------------------------------------------");
    }

    /** BR-CBACT01C-02..04 with the Q-CBACT01C-01 and Q-CBACT01C-02 fixes. */
    private String outRecord(String r) {
        String reissue;
        try {
            reissue = LocalDate.parse(r.substring(68, 78), ISO).format(DateTimeFormatter.BASIC_ISO_DATE) + "  ";
        } catch (DateTimeParseException e) {
            log.display(ABENDING);
            throw new Abend();
        }
        BigInteger debit = Overpunch.decode(r.substring(90, 102), 2).unscaledValue();
        byte[] packed = new byte[7];
        Comp.packed(packed, 0, 7, debit.signum() == 0 ? DEFAULT_DEBIT : debit, 12, true);
        return r.substring(0, 68) + reissue + r.substring(78, 90) + new String(packed, ISO_8859_1) + r.substring(112, 122);
    }

    /** BR-CBACT01C-05: the balance array with its constants (Q-CBACT01C-03). */
    static byte[] arrayRecord(String r) {
        byte[] a = new byte[ARRAY_LENGTH];
        String bal = r.substring(12, 24);
        String zero = Overpunch.encode(BigDecimal.ZERO, 12, 2);
        String[] balances = {bal, bal, Overpunch.encode(new BigDecimal("-1025.00"), 12, 2), zero, zero};
        long[] debits = {100500, 152500, -250000, 0, 0};
        System.arraycopy(r.substring(0, 11).getBytes(ISO_8859_1), 0, a, 0, 11);
        for (int i = 0; i < 5; i++) {
            int off = 11 + i * 19;
            System.arraycopy(balances[i].getBytes(ISO_8859_1), 0, a, off, 12);
            Comp.packed(a, off + 12, 7, BigInteger.valueOf(debits[i]), 12, true);
        }
        for (int i = 106; i < ARRAY_LENGTH; i++) {
            a[i] = ' ';
        }
        return a;
    }

    /** A RECFM=VB record: 2-byte big-endian length (data + 4), 2 zero bytes, then the data. */
    static byte[] variable(String data) {
        byte[] d = data.getBytes(ISO_8859_1);
        byte[] v = new byte[d.length + 4];
        Comp.binary(v, 0, 2, d.length + 4);
        System.arraycopy(d, 0, v, 4, d.length);
        return v;
    }

    private RecordSink openOutput(Path path, String message, List<Path> created, List<RecordSink> open) {
        try {
            RecordSink s = sinks.create(path);
            created.add(path);
            open.add(s);
            return s;
        } catch (IOException e) {
            String status = FileStatuses.openOutput(e);
            abend(message + status, status);
            return null;
        }
    }

    private void write(RecordSink sink, byte[] data) {
        try {
            sink.write(data);
        } catch (IOException e) {
            String status = FileStatuses.write(e);
            abend("ACCOUNT FILE WRITE STATUS IS:" + status, status);
        }
    }

    private void abend(String message, String status) {
        log.display(message);
        log.display(FileStatuses.statusLine(status));
        log.display(ABENDING);
        throw new Abend();
    }

    private static void discard(List<RecordSink> open, List<Path> created) {
        for (RecordSink s : open) {
            try {
                s.close();
            } catch (IOException ignored) {
                // the file is deleted next
            }
        }
        for (Path p : created) {
            try {
                Files.deleteIfExists(p);
            } catch (IOException e) {
                System.err.println("could not delete " + p + ": " + e);
            }
        }
    }
}
