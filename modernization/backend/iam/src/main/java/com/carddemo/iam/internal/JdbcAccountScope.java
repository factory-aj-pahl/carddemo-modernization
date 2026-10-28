package com.carddemo.iam.internal;

import com.carddemo.common.security.AccountScope;
import com.carddemo.common.security.CurrentUser;
import java.util.LinkedHashSet;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** C-SCOPE over the user_account links (D-USER-ACCT, A-3). */
@Component
public class JdbcAccountScope implements AccountScope {

    private final JdbcClient jdbc;

    public JdbcAccountScope(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean canAccess(CurrentUser user, long accountId) {
        if (user.isAdmin()) {
            return true;
        }
        return jdbc.sql("SELECT COUNT(*) FROM user_account WHERE user_id = ? AND account_id = ?")
            .params(user.userId(), accountId).query(Integer.class).single() > 0;
    }

    @Override
    public Set<Long> linkedAccounts(String userId) {
        return new LinkedHashSet<>(jdbc.sql("SELECT account_id FROM user_account WHERE user_id = ? ORDER BY account_id")
            .param(userId).query(Long.class).list());
    }
}
