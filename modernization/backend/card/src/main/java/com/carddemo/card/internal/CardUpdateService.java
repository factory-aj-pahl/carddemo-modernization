package com.carddemo.card.internal;

import com.carddemo.card.internal.CardRepository.CardRecord;
import com.carddemo.common.audit.AuditLog;
import com.carddemo.common.mask.Masking;
import java.io.Serializable;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;

/** COCRDUPC 9200-WRITE-PROCESSING: lock, concurrency check against the fetched values, rewrite, audit. */
@Service
public class CardUpdateService {

    /**
     * The card values as fetched for display (CCUP-OLD-DETAILS): name upper-cased (BR-COCRDUPC-02),
     * expiry split into year, month, day text ({@code ""} when the stored date is empty).
     */
    public record Snapshot(String cvv, String name, String year, String month, String day, String status)
        implements Serializable {

        public static Snapshot of(CardRecord c) {
            LocalDate d = c.expiry();
            return new Snapshot(c.cvv(), upper(c.embossedName()),
                d == null ? "" : String.format("%04d", d.getYear()),
                d == null ? "" : String.format("%02d", d.getMonthValue()),
                d == null ? "" : String.format("%02d", d.getDayOfMonth()),
                c.activeStatus());
        }
    }

    public sealed interface Outcome {
    }

    /** {@code saved}: the row after the rewrite. */
    public record Saved(CardRecord saved) implements Outcome {
    }

    /** ERR-COCRDUPC-04: the record differs from the fetched values; {@code current} is what is stored now. */
    public record Changed(CardRecord current) implements Outcome {
    }

    /** ERR-COCRDUPC-02: READ UPDATE failed (record gone or not lockable). */
    public record LockFailed() implements Outcome {
    }

    /** ERR-COCRDUPC-03: REWRITE failed. */
    public record WriteFailed() implements Outcome {
    }

    private final CardRepository cards;
    /** Resolved per call: modules that depend on card without iam start contexts with no AuditLog bean. */
    private final ObjectProvider<AuditLog> audit;

    public CardUpdateService(CardRepository cards, ObjectProvider<AuditLog> audit) {
        this.cards = cards;
        this.audit = audit;
    }

    /**
     * Saves the confirmed changes. Only name, status, and expiry are written; the expiry day is the
     * fetched day, clamped to the last day of the new month (DF-7, Q-COCRDUPC-06).
     */
    @Transactional(timeout = 10)
    public Outcome save(String actor, String cardNum, Snapshot old, String name, String status, int month, int year) {
        Optional<CardRecord> locked;
        try {
            locked = cards.findForUpdate(cardNum);
        } catch (DataAccessException e) {
            return new LockFailed();
        }
        if (locked.isEmpty()) {
            return new LockFailed();
        }
        CardRecord current = locked.get();
        if (!Snapshot.of(current).equals(old)) {
            return new Changed(current);
        }
        LocalDate expiry = expiry(old.day(), month, year);
        try {
            if (cards.updateDetails(cardNum, name, status, expiry) != 1) {
                TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
                return new WriteFailed();
            }
            List<String> changed = new ArrayList<>();
            if (!name.equals(current.embossedName())) {
                changed.add("embossedName");
            }
            if (!status.equals(current.activeStatus())) {
                changed.add("activeStatus");
            }
            if (!expiry.equals(current.expiry())) {
                changed.add("expirationDate");
            }
            audit.getObject().record(actor, "CARD_UPDATE", Masking.card(cardNum), changed);
        } catch (DataAccessException e) {
            TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
            return new WriteFailed();
        }
        return new Saved(new CardRecord(cardNum, current.accountId(), current.cvv(), name, expiry, status));
    }

    static LocalDate expiry(String day, int month, int year) {
        YearMonth ym = YearMonth.of(year, month);
        int d = day.isEmpty() ? 1 : Integer.parseInt(day);
        return ym.atDay(Math.min(d, ym.lengthOfMonth()));
    }

    static String upper(String s) {
        return s == null ? "" : s.toUpperCase(Locale.ROOT);
    }
}
