package com.carddemo.cycle.internal;

import java.math.BigDecimal;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** The transaction-category balance table (TCATBALF), owned by the batch cycle context. */
@Component
class CategoryBalances {

    private final JdbcClient jdbc;

    CategoryBalances(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    Optional<BigDecimal> lock(long accountId, String typeCd, int catCd) {
        return jdbc.sql("""
                SELECT balance FROM tran_cat_balance WITH (UPDLOCK, ROWLOCK)
                WHERE account_id = ? AND type_cd = ? AND cat_cd = ?
                """)
            .params(accountId, typeCd, catCd).query(BigDecimal.class).optional();
    }

    void insert(long accountId, String typeCd, int catCd, BigDecimal balance) {
        jdbc.sql("INSERT INTO tran_cat_balance (account_id, type_cd, cat_cd, balance) VALUES (?, ?, ?, ?)")
            .params(accountId, typeCd, catCd, balance).update();
    }

    boolean add(long accountId, String typeCd, int catCd, BigDecimal amount) {
        return jdbc.sql("UPDATE tran_cat_balance SET balance = balance + ? WHERE account_id = ? AND type_cd = ? AND cat_cd = ?")
            .params(amount, accountId, typeCd, catCd).update() == 1;
    }
}
