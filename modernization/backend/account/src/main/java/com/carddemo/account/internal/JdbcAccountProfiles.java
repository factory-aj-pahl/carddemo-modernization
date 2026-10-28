package com.carddemo.account.internal;

import com.carddemo.account.api.AccountLimits;
import com.carddemo.account.api.AccountProfiles;
import com.carddemo.account.api.CustomerContact;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

@Component
class JdbcAccountProfiles implements AccountProfiles {

    private final JdbcClient jdbc;

    JdbcAccountProfiles(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<AccountLimits> limits(long accountId) {
        return jdbc.sql("SELECT account_id, active_status, current_balance, credit_limit, cash_credit_limit FROM account "
            + "WHERE account_id = ?").param(accountId)
            .query((rs, i) -> new AccountLimits(rs.getLong(1), rs.getString(2), rs.getBigDecimal(3), rs.getBigDecimal(4),
                rs.getBigDecimal(5)))
            .optional();
    }

    @Override
    public Optional<CustomerContact> contact(long customerId) {
        return jdbc.sql("SELECT customer_id, first_name, middle_name, last_name, addr_line1, addr_line2, city, state_cd, zip, "
            + "phone1 FROM customer WHERE customer_id = ?").param(customerId)
            .query((rs, i) -> new CustomerContact(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
                rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8), rs.getString(9), rs.getString(10)))
            .optional();
    }
}
