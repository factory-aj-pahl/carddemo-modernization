package com.carddemo.card.internal;

import com.carddemo.card.api.CardXref;
import com.carddemo.card.api.CardXrefListing;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

@Component
class JdbcCardXrefListing implements CardXrefListing {

    private final JdbcClient jdbc;

    JdbcCardXrefListing(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<CardXref> allByCardNumber() {
        return jdbc.sql("SELECT card_num, customer_id, account_id FROM card_xref ORDER BY card_num")
            .query((rs, i) -> new CardXref(rs.getString(1), rs.getLong(2), rs.getLong(3)))
            .list();
    }
}
