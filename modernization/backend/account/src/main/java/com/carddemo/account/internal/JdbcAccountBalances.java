package com.carddemo.account.internal;

import com.carddemo.account.api.AccountBalances;
import java.math.BigDecimal;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
class JdbcAccountBalances implements AccountBalances {

    private final JdbcClient jdbc;

    JdbcAccountBalances(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<BigDecimal> currentBalance(long accountId) {
        return jdbc.sql("SELECT current_balance FROM account WHERE account_id = ?")
            .param(accountId).query(BigDecimal.class).optional();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<BigDecimal> lockCurrentBalance(long accountId) {
        return jdbc.sql("SELECT current_balance FROM account WITH (UPDLOCK, ROWLOCK) WHERE account_id = ?")
            .param(accountId).query(BigDecimal.class).optional();
    }

    @Override
    public boolean updateCurrentBalance(long accountId, BigDecimal newBalance) {
        return jdbc.sql("UPDATE account SET current_balance = ? WHERE account_id = ?")
            .params(newBalance, accountId).update() == 1;
    }
}
