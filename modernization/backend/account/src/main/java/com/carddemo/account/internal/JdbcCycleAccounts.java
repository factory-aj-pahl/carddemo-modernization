package com.carddemo.account.internal;

import com.carddemo.account.api.CycleAccounts;
import com.carddemo.account.api.InterestAccount;
import com.carddemo.account.api.StatementAccount;
import com.carddemo.account.api.StatementCustomer;
import java.math.BigDecimal;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
class JdbcCycleAccounts implements CycleAccounts {

    private final JdbcClient jdbc;

    JdbcCycleAccounts(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<InterestAccount> lockForInterest(long accountId) {
        return jdbc.sql("SELECT account_id, group_id, current_balance FROM account WITH (UPDLOCK, ROWLOCK) WHERE account_id = ?")
            .param(accountId)
            .query((rs, i) -> new InterestAccount(rs.getLong(1), rs.getString(2), rs.getBigDecimal(3)))
            .optional();
    }

    @Override
    public boolean applyInterest(long accountId, BigDecimal newBalance) {
        return jdbc.sql("""
                UPDATE account SET current_balance = ?, curr_cycle_credit = 0, curr_cycle_debit = 0
                WHERE account_id = ?
                """)
            .params(newBalance, accountId).update() == 1;
    }

    @Override
    public boolean exists(long accountId) {
        return jdbc.sql("SELECT COUNT(*) FROM account WHERE account_id = ?").param(accountId).query(Integer.class).single() > 0;
    }

    @Override
    public Optional<StatementAccount> statementAccount(long accountId) {
        return jdbc.sql("SELECT account_id, current_balance FROM account WHERE account_id = ?")
            .param(accountId)
            .query((rs, i) -> new StatementAccount(rs.getLong(1), rs.getBigDecimal(2)))
            .optional();
    }

    @Override
    public Optional<StatementCustomer> statementCustomer(long customerId) {
        return jdbc.sql("""
                SELECT customer_id, first_name, middle_name, last_name, addr_line1, addr_line2, city, state_cd,
                    country_cd, zip, fico_score
                FROM customer WHERE customer_id = ?
                """)
            .param(customerId)
            .query((rs, i) -> new StatementCustomer(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
                rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8), rs.getString(9), rs.getString(10),
                rs.getInt(11)))
            .optional();
    }
}
