package com.carddemo.account.internal;

import com.carddemo.account.api.AccountPostings;
import com.carddemo.account.api.PostingAccount;
import java.math.BigDecimal;
import java.sql.Date;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
class JdbcAccountPostings implements AccountPostings {

    private final JdbcClient jdbc;

    JdbcAccountPostings(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<PostingAccount> lockForPosting(long accountId) {
        return jdbc.sql("""
                SELECT account_id, credit_limit, curr_cycle_credit, curr_cycle_debit, expiration_date
                FROM account WITH (UPDLOCK, ROWLOCK) WHERE account_id = ?
                """)
            .param(accountId)
            .query((rs, i) -> {
                Date exp = rs.getDate(5);
                return new PostingAccount(rs.getLong(1), rs.getBigDecimal(2), rs.getBigDecimal(3), rs.getBigDecimal(4),
                    exp == null ? " ".repeat(10) : exp.toLocalDate().toString());
            })
            .optional();
    }

    @Override
    public boolean applyPosting(long accountId, BigDecimal amount) {
        return jdbc.sql("""
                UPDATE account SET current_balance = current_balance + :amt,
                    curr_cycle_credit = curr_cycle_credit + CASE WHEN :amt >= 0 THEN :amt ELSE 0 END,
                    curr_cycle_debit = curr_cycle_debit + CASE WHEN :amt < 0 THEN :amt ELSE 0 END
                WHERE account_id = :id
                """)
            .param("amt", amount).param("id", accountId).update() == 1;
    }
}
