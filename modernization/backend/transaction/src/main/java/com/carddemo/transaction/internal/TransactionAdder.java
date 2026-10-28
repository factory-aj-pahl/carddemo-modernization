package com.carddemo.transaction.internal;

import com.carddemo.transaction.api.NewTransaction;
import com.carddemo.transaction.api.TransactionLedger;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** COTRN02C ADD-TRANSACTION: takes the next online TRAN-ID and writes the row in one database transaction. */
@Service
public class TransactionAdder {

    public static final String LOOKUP_FAILED = "Unable to lookup Transaction...";
    public static final String DUPLICATE = "Tran ID already exist...";
    public static final String ADD_FAILED = "Unable to Add Transaction...";

    /** The validated form content to store (BR-COTRN02C-06). */
    public record Entry(String cardNum, String typeCd, int catCd, String source, String description, BigDecimal amount,
                        long merchantId, String merchantName, String merchantCity, String merchantZip,
                        LocalDate origDate, LocalDate procDate) {
    }

    /** A step failed; nothing was written and {@link #getMessage()} is the screen text. */
    public static class AddFailed extends RuntimeException {
        AddFailed(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private final TransactionLedger ledger;

    TransactionAdder(TransactionLedger ledger) {
        this.ledger = ledger;
    }

    /** Returns the new 16-digit TRAN-ID. */
    @Transactional
    public String add(Entry e) {
        String tranId;
        try {
            tranId = ledger.nextOnlineTransactionId();
        } catch (DataAccessException ex) {
            throw new AddFailed(LOOKUP_FAILED, ex);
        }
        // Q-COTRN02C-03: both timestamps are the typed date with no time part.
        String orig = e.origDate().toString();
        String proc = e.procDate().toString();
        try {
            ledger.insert(new NewTransaction(tranId, e.typeCd(), e.catCd(), e.source(), e.description(), e.amount(),
                e.merchantId(), e.merchantName(), e.merchantCity(), e.merchantZip(), e.cardNum(),
                orig, e.origDate().atStartOfDay(), proc, e.procDate().atStartOfDay()));
        } catch (DuplicateKeyException ex) {
            throw new AddFailed(DUPLICATE, ex);
        } catch (DataAccessException ex) {
            throw new AddFailed(ADD_FAILED, ex);
        }
        return tranId;
    }
}
