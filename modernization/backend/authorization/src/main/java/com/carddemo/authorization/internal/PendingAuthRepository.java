package com.carddemo.authorization.internal;

import com.carddemo.migration.PendingAuthSegments.Detail;
import com.carddemo.migration.PendingAuthSegments.Summary;
import com.carddemo.migration.PendingAuthTables;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Pending authorizations (the DBPAUTP0 summary root and detail children) plus the account and customer fields
 * the module reads. The account and customer rows are read here directly because the Account context publishes
 * no read of limits, status, or customer contact data (architecture.md, deviations in Authorizations).
 */
@Repository
public class PendingAuthRepository {

    /** ACCTDAT fields COPAUA0C and COPAUS0C use. */
    public record AccountRow(long accountId, String activeStatus, BigDecimal currentBalance, BigDecimal creditLimit,
            BigDecimal cashCreditLimit) {
    }

    /** CUSTDAT fields COPAUS0C shows. */
    public record CustomerRow(long customerId, String firstName, String middleName, String lastName, String addrLine1,
            String addrLine2, String city, String state, String zip, String phone1) {
    }

    private final JdbcTemplate jdbc;

    public PendingAuthRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<AccountRow> account(long accountId) {
        return jdbc.query("SELECT account_id, active_status, current_balance, credit_limit, cash_credit_limit FROM account "
            + "WHERE account_id = ?", (rs, i) -> new AccountRow(rs.getLong(1), rs.getString(2), rs.getBigDecimal(3),
                rs.getBigDecimal(4), rs.getBigDecimal(5)), accountId).stream().findFirst();
    }

    public Optional<CustomerRow> customer(long customerId) {
        return jdbc.query("SELECT customer_id, first_name, middle_name, last_name, addr_line1, addr_line2, city, state_cd, zip, "
            + "phone1 FROM customer WHERE customer_id = ?", (rs, i) -> new CustomerRow(rs.getLong(1), rs.getString(2),
                rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8),
                rs.getString(9), rs.getString(10)), customerId).stream().findFirst();
    }

    public Optional<Summary> summary(long accountId) {
        return jdbc.query("SELECT * FROM pending_auth_summary WHERE account_id = ?", PendingAuthTables.SUMMARY, accountId)
            .stream().findFirst();
    }

    /** GU with hold: the summary row stays locked until the transaction ends. */
    public Optional<Summary> lockSummary(long accountId) {
        return jdbc.query("SELECT * FROM pending_auth_summary WITH (UPDLOCK, HOLDLOCK) WHERE account_id = ?",
            PendingAuthTables.SUMMARY, accountId).stream().findFirst();
    }

    public void insertSummary(Summary s) {
        PendingAuthTables.insertSummary(jdbc, s);
    }

    /** REPL: the business fields of an existing summary (legacy bytes are kept). */
    public boolean updateSummary(Summary s) {
        return jdbc.update("UPDATE pending_auth_summary SET customer_id = ?, credit_limit = ?, cash_limit = ?, credit_balance = ?, "
            + "cash_balance = ?, approved_count = ?, declined_count = ?, approved_amt = ?, declined_amt = ? WHERE account_id = ?",
            s.customerId(), s.creditLimit(), s.cashLimit(), s.creditBalance(), s.cashBalance(), s.approvedCount(),
            s.declinedCount(), s.approvedAmt(), s.declinedAmt(), s.accountId()) == 1;
    }

    public boolean deleteSummary(long accountId) {
        return jdbc.update("DELETE FROM pending_auth_summary WHERE account_id = ?", accountId) == 1;
    }

    public long insertDetail(Detail d) {
        return PendingAuthTables.insertDetail(jdbc, d);
    }

    public Optional<Detail> detail(long accountId, long authId) {
        return jdbc.query("SELECT * FROM pending_auth_detail WHERE account_id = ? AND auth_id = ?", PendingAuthTables.DETAIL,
            accountId, authId).stream().findFirst();
    }

    public Optional<Detail> lockDetail(long accountId, long authId) {
        return jdbc.query("SELECT * FROM pending_auth_detail WITH (UPDLOCK, HOLDLOCK) WHERE account_id = ? AND auth_id = ?",
            PendingAuthTables.DETAIL, accountId, authId).stream().findFirst();
    }

    /** Up to {@code limit} details of the account in newest-first order, starting at the key (inclusive) or the first. */
    public List<Detail> detailsFrom(long accountId, LocalDateTime ts, Long authId, boolean inclusive, int limit) {
        if (ts == null) {
            return jdbc.query("SELECT TOP (?) * FROM pending_auth_detail WHERE account_id = ? ORDER BY "
                + PendingAuthTables.DETAIL_ORDER, PendingAuthTables.DETAIL, limit, accountId);
        }
        String cmp = inclusive ? "<=" : "<";
        return jdbc.query("SELECT TOP (?) * FROM pending_auth_detail WHERE account_id = ? AND (auth_ts < ? OR (auth_ts = ? AND auth_id "
            + cmp + " ?)) ORDER BY " + PendingAuthTables.DETAIL_ORDER, PendingAuthTables.DETAIL, limit, accountId,
            java.sql.Timestamp.valueOf(ts), java.sql.Timestamp.valueOf(ts), authId);
    }

    /** The next older detail after the given one (COPAUS1C PF8 GNP), if any. */
    public Optional<Detail> nextOlder(long accountId, LocalDateTime ts, long authId) {
        return detailsFrom(accountId, ts, authId, false, 1).stream().findFirst();
    }

    /** COPAUS2C detail side: fraud flag and report date (Q-ENT-28). */
    public boolean updateFraud(long authId, String flag, java.time.LocalDate reportDate) {
        return jdbc.update("UPDATE pending_auth_detail SET auth_fraud = ?, fraud_rpt_date = ? WHERE auth_id = ?", flag,
            reportDate == null ? null : java.sql.Date.valueOf(reportDate), authId) == 1;
    }
}
