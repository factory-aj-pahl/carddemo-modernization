package com.carddemo.account.internal;

import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Reads and column-by-name updates of the account and customer tables (Q-COACTUPC-01). */
@Component
public class AccountCustomerStore {

    private static final String ACCOUNT_COLUMNS = """
        account_id, active_status, current_balance, credit_limit, cash_credit_limit, open_date, expiration_date,
        reissue_date, curr_cycle_credit, curr_cycle_debit, group_id, row_version""";
    private static final String CUSTOMER_COLUMNS = """
        customer_id, first_name, middle_name, last_name, addr_line1, addr_line2, city, state_cd, country_cd, zip,
        phone1, phone2, ssn, govt_issued_id, dob, eft_account_id, pri_card_holder, fico_score, row_version""";

    private static final RowMapper<AccountRecord> ACCOUNT = (rs, n) -> new AccountRecord(rs.getLong("account_id"),
        rs.getString("active_status"), rs.getBigDecimal("current_balance"), rs.getBigDecimal("credit_limit"),
        rs.getBigDecimal("cash_credit_limit"), rs.getObject("open_date", java.time.LocalDate.class),
        rs.getObject("expiration_date", java.time.LocalDate.class), rs.getObject("reissue_date", java.time.LocalDate.class),
        rs.getBigDecimal("curr_cycle_credit"), rs.getBigDecimal("curr_cycle_debit"), rs.getString("group_id"),
        rs.getBytes("row_version"));

    private static final RowMapper<CustomerRecord> CUSTOMER = (rs, n) -> new CustomerRecord(rs.getInt("customer_id"),
        rs.getString("first_name"), rs.getString("middle_name"), rs.getString("last_name"), rs.getString("addr_line1"),
        rs.getString("addr_line2"), rs.getString("city"), rs.getString("state_cd"), rs.getString("country_cd"),
        rs.getString("zip"), rs.getString("phone1"), rs.getString("phone2"), rs.getString("ssn").strip(),
        rs.getString("govt_issued_id"), rs.getObject("dob", java.time.LocalDate.class), rs.getString("eft_account_id"),
        rs.getString("pri_card_holder"), rs.getInt("fico_score"), rs.getBytes("row_version"));

    private final JdbcClient jdbc;
    private final int lockTimeoutMs;

    public AccountCustomerStore(JdbcClient jdbc, @Value("${carddemo.account.lock-timeout-ms:5000}") int lockTimeoutMs) {
        this.jdbc = jdbc;
        this.lockTimeoutMs = lockTimeoutMs;
    }

    public Optional<AccountRecord> account(long accountId) {
        return jdbc.sql("SELECT " + ACCOUNT_COLUMNS + " FROM account WHERE account_id = ?").param(accountId)
            .query(ACCOUNT).optional();
    }

    public Optional<CustomerRecord> customer(long customerId) {
        return jdbc.sql("SELECT " + CUSTOMER_COLUMNS + " FROM customer WHERE customer_id = ?").param(customerId)
            .query(CUSTOMER).optional();
    }

    /** READ UPDATE: holds an update lock until the surrounding transaction ends; waits at most the lock timeout. */
    public Optional<AccountRecord> lockAccount(long accountId) {
        return jdbc.sql(lockTimeout() + "SELECT " + ACCOUNT_COLUMNS
            + " FROM account WITH (UPDLOCK, ROWLOCK) WHERE account_id = ?").param(accountId).query(ACCOUNT).optional();
    }

    public Optional<CustomerRecord> lockCustomer(long customerId) {
        return jdbc.sql(lockTimeout() + "SELECT " + CUSTOMER_COLUMNS
            + " FROM customer WITH (UPDLOCK, ROWLOCK) WHERE customer_id = ?").param(customerId).query(CUSTOMER).optional();
    }

    /** Updates the edited account columns; ACCT-ADDR-ZIP is left as stored. True when one row was updated. */
    public boolean updateAccount(AccountRecord a) {
        return jdbc.sql("""
            UPDATE account SET active_status = ?, current_balance = ?, credit_limit = ?, cash_credit_limit = ?,
              open_date = ?, expiration_date = ?, reissue_date = ?, curr_cycle_credit = ?, curr_cycle_debit = ?, group_id = ?
            WHERE account_id = ?""")
            .params(a.activeStatus(), a.currentBalance(), a.creditLimit(), a.cashCreditLimit(), a.openDate(),
                a.expirationDate(), a.reissueDate(), a.cycleCredit(), a.cycleDebit(), a.groupId(), a.accountId())
            .update() == 1;
    }

    /** Updates the edited customer columns (the country is not editable online). True when one row was updated. */
    public boolean updateCustomer(CustomerRecord c) {
        return jdbc.sql("""
            UPDATE customer SET first_name = ?, middle_name = ?, last_name = ?, addr_line1 = ?, addr_line2 = ?, city = ?,
              state_cd = ?, zip = ?, phone1 = ?, phone2 = ?, ssn = ?, govt_issued_id = ?, dob = ?, eft_account_id = ?,
              pri_card_holder = ?, fico_score = ?
            WHERE customer_id = ?""")
            .params(c.firstName(), c.middleName(), c.lastName(), c.addrLine1(), c.addrLine2(), c.city(), c.stateCd(),
                c.zip(), c.phone1(), c.phone2(), c.ssn(), c.govtIssuedId(), c.dob(), c.eftAccountId(), c.priCardHolder(),
                c.ficoScore(), c.customerId())
            .update() == 1;
    }

    /**
     * Prefix of the locking read. The parameterized batch runs through sp_executesql, so the setting
     * applies to this batch only and never leaks into the pooled connection.
     */
    private String lockTimeout() {
        return "SET LOCK_TIMEOUT " + lockTimeoutMs + "; ";
    }
}
