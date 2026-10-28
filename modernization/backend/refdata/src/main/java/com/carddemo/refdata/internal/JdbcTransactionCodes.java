package com.carddemo.refdata.internal;

import com.carddemo.refdata.api.TransactionCodes;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

@Component
class JdbcTransactionCodes implements TransactionCodes {

    private final JdbcClient jdbc;

    JdbcTransactionCodes(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean typeExists(String typeCd) {
        return jdbc.sql("SELECT COUNT(*) FROM transaction_type WHERE type_cd = ?")
            .param(typeCd).query(Integer.class).single() > 0;
    }

    @Override
    public boolean categoryExists(String typeCd, int catCd) {
        return jdbc.sql("SELECT COUNT(*) FROM transaction_category WHERE type_cd = ? AND cat_cd = ?")
            .params(typeCd, catCd).query(Integer.class).single() > 0;
    }
}
