package com.carddemo.account.internal;

import com.carddemo.account.api.AccountInquiry;
import java.sql.Date;
import java.time.LocalDate;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

@Component
class JdbcAccountInquiry implements AccountInquiry {

    private final JdbcClient jdbc;

    JdbcAccountInquiry(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<AccountSnapshot> find(long accountId) {
        return jdbc.sql("""
                SELECT account_id, active_status, current_balance, credit_limit, cash_credit_limit, open_date,
                       expiration_date, reissue_date, curr_cycle_credit, curr_cycle_debit, group_id
                  FROM account WHERE account_id = ?""")
            .param(accountId)
            .query((rs, i) -> new AccountSnapshot(rs.getLong(1), rs.getString(2), rs.getBigDecimal(3), rs.getBigDecimal(4),
                rs.getBigDecimal(5), date(rs.getDate(6)), date(rs.getDate(7)), date(rs.getDate(8)), rs.getBigDecimal(9),
                rs.getBigDecimal(10), rs.getString(11)))
            .optional();
    }

    private static LocalDate date(Date d) {
        return d == null ? null : d.toLocalDate();
    }
}
