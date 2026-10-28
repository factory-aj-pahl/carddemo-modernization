package com.carddemo.cycle.internal;

import com.carddemo.account.api.CycleAccounts;
import com.carddemo.account.api.InterestAccount;
import com.carddemo.card.api.CardDirectory;
import com.carddemo.common.batch.BatchAbend;
import com.carddemo.common.batch.JobLog;
import com.carddemo.common.codec.FixedWidth;
import com.carddemo.common.codec.LegacyTimestamps;
import com.carddemo.cycle.api.InterestCalculation;
import com.carddemo.refdata.api.DisclosureRates;
import com.carddemo.transaction.api.NewTransaction;
import com.carddemo.transaction.api.TransactionLedger;
import com.carddemo.transaction.api.TransactionQueries;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** CBACT04C main loop for one account: 1100, 1110, 1200, 1300 and 1050 (spec/programs/CBACT04C.md). */
@Service
class InterestService implements InterestCalculation {

    static final String DEFAULT_GROUP = "DEFAULT";
    static final long MAX_SUFFIX = 999_999;
    private static final BigDecimal TWELVE_HUNDRED = BigDecimal.valueOf(1200);

    private final JdbcClient jdbc;
    private final CycleAccounts accounts;
    private final CardDirectory cards;
    private final DisclosureRates rates;
    private final TransactionLedger ledger;
    private final TransactionQueries transactions;
    private final JobLog log;
    private final Clock clock;

    InterestService(JdbcClient jdbc, CycleAccounts accounts, CardDirectory cards, DisclosureRates rates,
            TransactionLedger ledger, TransactionQueries transactions, JobLog log, Clock clock) {
        this.jdbc = jdbc;
        this.accounts = accounts;
        this.cards = cards;
        this.rates = rates;
        this.ledger = ledger;
        this.transactions = transactions;
        this.log = log;
        this.clock = clock;
    }

    record CategoryBalance(long accountId, String typeCd, int catCd, BigDecimal balance, String filler) {
        /** TRAN-CAT-BAL-RECORD as CBACT04C displays it (50 bytes, sign overpunched). */
        String legacy() {
            return FixedWidth.n(accountId, 11) + FixedWidth.x(typeCd, 2) + FixedWidth.n(catCd, 4)
                + FixedWidth.s(balance, 11, 2) + FixedWidth.x(filler, 22);
        }
    }

    @Override
    public long lastSuffix(String idPrefix) {
        return transactions.highestIdWithPrefix(idPrefix)
            .map(id -> id.substring(idPrefix.length()).strip())
            .filter(s -> !s.isEmpty() && s.chars().allMatch(Character::isDigit))
            .map(Long::parseLong).orElse(0L);
    }

    @Override
    public Optional<Long> nextAccount(String interestMonth, long afterAccountId) {
        try {
            return jdbc.sql("""
                    SELECT MIN(b.account_id) FROM tran_cat_balance b
                    WHERE b.account_id > ? AND NOT EXISTS (
                        SELECT 1 FROM interest_account i WHERE i.interest_month = ? AND i.account_id = b.account_id)
                    """)
                .params(afterAccountId, interestMonth).query(Long.class).optional();
        } catch (DataAccessException e) {
            throw new BatchAbend("ERROR READING TRANSACTION CATEGORY FILE", null, e);
        }
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public long chargeAccount(long accountId, LocalDate businessDate, long lastSuffix) {
        String idPrefix = InterestCalculation.idPrefix(businessDate);
        List<CategoryBalance> balances = categoryBalances(accountId);
        if (balances.isEmpty()) {
            return lastSuffix;
        }
        long suffix = lastSuffix;
        BigDecimal totalInterest = BigDecimal.ZERO.setScale(2);
        int rows = 0;
        InterestAccount account = null;
        String cardNum = null;
        for (CategoryBalance b : balances) {
            log.display(b.legacy());
            if (account == null) {
                account = readAccount(accountId);
                cardNum = readCard(accountId);
            }
            BigDecimal rate = interestRate(account.groupId(), b.typeCd(), b.catCd());
            if (rate.signum() != 0) {
                // BR-CBACT04C-04: truncated to 2 decimals; high-order digits beyond S9(9) are lost (no SIZE ERROR).
                BigDecimal monthly = fit(b.balance().multiply(rate).divide(TWELVE_HUNDRED, 2, RoundingMode.DOWN), 9);
                totalInterest = fit(totalInterest.add(monthly), 9);
                if (suffix >= MAX_SUFFIX) {
                    // Q-CBACT04C-08: the 6-digit suffix would wrap and repeat an ID.
                    throw new BatchAbend("ERROR WRITING TRANSACTION RECORD", null);
                }
                suffix++;
                writeInterest(idPrefix + FixedWidth.n(suffix, 6), account.accountId(), monthly, cardNum);
                rows++;
            }
        }
        // BR-CBACT04C-07 and Q-CBACT04C-01 (fixed): every account, including the last, is updated.
        BigDecimal newBalance = fit(account.currentBalance().add(totalInterest), 10);
        boolean updated;
        try {
            updated = accounts.applyInterest(accountId, newBalance);
        } catch (DataAccessException e) {
            throw new BatchAbend("ERROR RE-WRITING ACCOUNT FILE", null, e);
        }
        if (!updated) {
            throw new BatchAbend("ERROR RE-WRITING ACCOUNT FILE", "23");
        }
        jdbc.sql("""
                INSERT INTO interest_account (interest_month, account_id, business_date, interest_rows, total_interest, processed_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """)
            .params(InterestCalculation.interestMonth(businessDate), accountId, businessDate, rows, totalInterest,
                LocalDateTime.now(clock))
            .update();
        return suffix;
    }

    private List<CategoryBalance> categoryBalances(long accountId) {
        try {
            return jdbc.sql("""
                    SELECT account_id, type_cd, cat_cd, balance, legacy_filler FROM tran_cat_balance
                    WHERE account_id = ? ORDER BY type_cd, cat_cd
                    """)
                .param(accountId)
                .query((rs, i) -> new CategoryBalance(rs.getLong(1), rs.getString(2), rs.getInt(3), rs.getBigDecimal(4),
                    rs.getString(5)))
                .list();
        } catch (DataAccessException e) {
            throw new BatchAbend("ERROR READING TRANSACTION CATEGORY FILE", null, e);
        }
    }

    private InterestAccount readAccount(long accountId) {
        Optional<InterestAccount> a;
        try {
            a = accounts.lockForInterest(accountId);
        } catch (DataAccessException e) {
            throw new BatchAbend("ERROR READING ACCOUNT FILE", null, e);
        }
        if (a.isEmpty()) {
            log.display("ACCOUNT NOT FOUND: " + FixedWidth.n(accountId, 11));
            throw new BatchAbend("ERROR READING ACCOUNT FILE", "23");
        }
        return a.get();
    }

    /** 1110-GET-XREF-DATA by account; with several cards the lowest card number (Q-CBACT04C-03, DF-6). */
    private String readCard(long accountId) {
        Optional<String> card;
        try {
            card = cards.lowestCardForAccount(accountId);
        } catch (DataAccessException e) {
            throw new BatchAbend("ERROR READING XREF FILE", null, e);
        }
        if (card.isEmpty()) {
            log.display("ACCOUNT NOT FOUND: " + FixedWidth.n(accountId, 11));
            throw new BatchAbend("ERROR READING XREF FILE", "23");
        }
        return card.get();
    }

    /** 1200-GET-INTEREST-RATE with the DEFAULT-group fallback (BR-CBACT04C-02). */
    private BigDecimal interestRate(String groupId, String typeCd, int catCd) {
        Optional<BigDecimal> rate;
        try {
            rate = rates.interestRate(groupId, typeCd, catCd);
        } catch (DataAccessException e) {
            throw new BatchAbend("ERROR READING DISCLOSURE GROUP FILE", null, e);
        }
        if (rate.isPresent()) {
            return rate.get();
        }
        log.display("DISCLOSURE GROUP RECORD MISSING");
        log.display("TRY WITH DEFAULT GROUP CODE");
        try {
            rate = rates.interestRate(DEFAULT_GROUP, typeCd, catCd);
        } catch (DataAccessException e) {
            throw new BatchAbend("ERROR READING DEFAULT DISCLOSURE GROUP", null, e);
        }
        return rate.orElseThrow(() -> new BatchAbend("ERROR READING DEFAULT DISCLOSURE GROUP", "23"));
    }

    /** 1300-B-WRITE-TX (BR-CBACT04C-06); the row goes straight into the transaction table (B-CYCLE). */
    private void writeInterest(String tranId, long accountId, BigDecimal amount, String cardNum) {
        LocalDateTime now = LocalDateTime.now(clock);
        String ts = LegacyTimestamps.db2(now);
        LocalDateTime parsed = LegacyTimestamps.parse(ts);
        try {
            ledger.insert(new NewTransaction(tranId, "01", 5, "System", "Int. for a/c " + FixedWidth.n(accountId, 11), amount,
                0, "", "", "", cardNum, ts, parsed, ts, parsed));
        } catch (DuplicateKeyException e) {
            throw new BatchAbend("ERROR WRITING TRANSACTION RECORD", "22", e);
        } catch (DataAccessException e) {
            throw new BatchAbend("ERROR WRITING TRANSACTION RECORD", null, e);
        }
    }

    /** A COBOL ADD/COMPUTE into S9(intDigits)V99 without ON SIZE ERROR: the high-order digits are dropped. */
    static BigDecimal fit(BigDecimal v, int intDigits) {
        return v.remainder(BigDecimal.TEN.pow(intDigits)).setScale(2, RoundingMode.DOWN);
    }
}
