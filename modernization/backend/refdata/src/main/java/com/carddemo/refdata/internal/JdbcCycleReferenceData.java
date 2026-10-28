package com.carddemo.refdata.internal;

import com.carddemo.refdata.api.DisclosureRates;
import com.carddemo.refdata.api.TransactionCodeNames;
import java.math.BigDecimal;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

@Component
class JdbcCycleReferenceData implements DisclosureRates, TransactionCodeNames {

    private final JdbcClient jdbc;

    JdbcCycleReferenceData(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<BigDecimal> interestRate(String groupId, String typeCd, int catCd) {
        return jdbc.sql("SELECT int_rate FROM disclosure_group WHERE group_id = ? AND type_cd = ? AND cat_cd = ?")
            .params(groupId, typeCd, catCd).query(BigDecimal.class).optional();
    }

    @Override
    public Optional<String> typeDescription(String typeCd) {
        return jdbc.sql("SELECT description FROM transaction_type WHERE type_cd = ?")
            .param(typeCd).query(String.class).optional();
    }

    @Override
    public Optional<String> categoryDescription(String typeCd, int catCd) {
        return jdbc.sql("SELECT description FROM transaction_category WHERE type_cd = ? AND cat_cd = ?")
            .params(typeCd, catCd).query(String.class).optional();
    }
}
