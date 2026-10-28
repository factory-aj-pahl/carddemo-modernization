package com.carddemo.card.internal;

import com.carddemo.card.api.CardDirectory;
import com.carddemo.card.api.CardXref;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

@Component
class JdbcCardDirectory implements CardDirectory {

    private final JdbcClient jdbc;

    JdbcCardDirectory(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<String> lowestCardForAccount(long accountId) {
        return jdbc.sql("SELECT MIN(card_num) FROM card_xref WHERE account_id = ?")
            .param(accountId).query(String.class).optional();
    }

    @Override
    public Optional<CardXref> findXref(String cardNum) {
        return jdbc.sql("SELECT card_num, customer_id, account_id FROM card_xref WHERE card_num = ?")
            .param(cardNum)
            .query((rs, i) -> new CardXref(rs.getString(1), rs.getLong(2), rs.getLong(3)))
            .optional();
    }

    @Override
    public List<String> cardsOfAccounts(Collection<Long> accountIds) {
        if (accountIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("SELECT card_num FROM card_xref WHERE account_id IN (:ids) ORDER BY card_num")
            .param("ids", List.copyOf(accountIds)).query(String.class).list();
    }
}
