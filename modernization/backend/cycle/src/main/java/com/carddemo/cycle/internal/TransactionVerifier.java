package com.carddemo.cycle.internal;

import com.carddemo.account.api.CycleAccounts;
import com.carddemo.card.api.CardDirectory;
import com.carddemo.card.api.CardXref;
import com.carddemo.common.batch.BatchAbend;
import com.carddemo.common.batch.JobLog;
import com.carddemo.common.batch.UnlabeledAbend;
import com.carddemo.common.codec.FixedWidth;
import com.carddemo.cycle.api.TransactionVerification;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Optional;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

/**
 * CBTRN01C as an on-request validation job (Q-CBTRN01C-06): for each daily record, the masked record, the
 * cross-reference lookup, and the account lookup on SYSOUT. Nothing is written (BR-CBTRN01C-05). Only the
 * daily file, the cross-reference, and the accounts are read (Q-CBTRN01C-04).
 */
@Service
class TransactionVerifier implements TransactionVerification {

    private final CardDirectory cards;
    private final CycleAccounts accounts;
    private final JobLog log;

    TransactionVerifier(CardDirectory cards, CycleAccounts accounts, JobLog log) {
        this.cards = cards;
        this.accounts = accounts;
        this.log = log;
    }

    @Override
    public long verify(Path dailyFile) {
        log.display("START OF EXECUTION OF PROGRAM CBTRN01C");
        long records = 0;
        try (BufferedReader reader = open(dailyFile)) {
            String line;
            while ((line = read(reader)) != null) {
                if (line.isEmpty() || line.equals("\r")) {
                    continue;
                }
                records++;
                check(FixedWidth.normalize(line, 350));
            }
        } catch (IOException e) {
            throw new BatchAbend("ERROR CLOSING CUSTOMER FILE", null, e);
        }
        log.display("END OF EXECUTION OF PROGRAM CBTRN01C");
        return records;
    }

    private static BufferedReader open(Path dailyFile) {
        try {
            return Files.newBufferedReader(dailyFile, StandardCharsets.ISO_8859_1);
        } catch (NoSuchFileException e) {
            throw new BatchAbend("ERROR OPENING DAILY TRANSACTION FILE", "35", e);
        } catch (IOException e) {
            throw new BatchAbend("ERROR OPENING DAILY TRANSACTION FILE", null, e);
        }
    }

    private static String read(BufferedReader reader) {
        try {
            return reader.readLine();
        } catch (IOException e) {
            throw new BatchAbend("ERROR READING DAILY TRANSACTION FILE", null, e);
        }
    }

    /** One record (Q-CBTRN01C-01 fixed: lookups only for records actually read). */
    private void check(String rec) {
        String tranId = rec.substring(0, 16);
        String cardNum = rec.substring(262, 278);
        // Q-CBTRN01C-05 / B-MASK: bytes 263-274 of the echoed record are masked.
        log.display(rec.substring(0, 262) + "*".repeat(12) + rec.substring(274));
        Optional<CardXref> xref;
        try {
            xref = cards.findXref(cardNum);
        } catch (DataAccessException e) {
            throw new UnlabeledAbend("cross-reference read failed (Q-CBTRN01C-03)", e);
        }
        if (xref.isEmpty()) {
            log.display("INVALID CARD NUMBER FOR XREF");
            log.display("CARD NUMBER " + maskedCard(cardNum) + " COULD NOT BE VERIFIED. SKIPPING TRANSACTION ID-" + tranId);
            return;
        }
        CardXref x = xref.get();
        log.display("SUCCESSFUL READ OF XREF");
        log.display("CARD NUMBER: " + maskedCard(x.cardNum()));
        log.display("ACCOUNT ID : " + FixedWidth.n(x.accountId(), 11));
        log.display("CUSTOMER ID: " + FixedWidth.n(x.customerId(), 9));
        boolean found;
        try {
            found = accounts.exists(x.accountId());
        } catch (DataAccessException e) {
            throw new UnlabeledAbend("account read failed (Q-CBTRN01C-03)", e);
        }
        if (found) {
            log.display("SUCCESSFUL READ OF ACCOUNT FILE");
        } else {
            log.display("INVALID ACCOUNT NUMBER FOUND");
            log.display("ACCOUNT " + FixedWidth.n(x.accountId(), 11) + " NOT FOUND");
        }
    }

    /** B-MASK: twelve asterisks and the last 4 of the 16-byte field (as RP-CBTRN01C-2 prints it). */
    private static String maskedCard(String card16) {
        String c = FixedWidth.x(card16, 16);
        return "*".repeat(12) + c.substring(12);
    }
}
