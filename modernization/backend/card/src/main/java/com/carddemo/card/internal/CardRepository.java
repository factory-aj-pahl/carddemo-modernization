package com.carddemo.card.internal;

import java.io.Serializable;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * CARDDAT over the {@code card} table. Browses use the primary key order (card_num, binary
 * collation) with indexed filters instead of a sequential scan (Q-COCRDLIC-05).
 */
@Repository
public class CardRepository {

    /** One card row; {@code expiry} may be null when the stored date is empty. */
    public record CardRecord(String cardNum, long accountId, String cvv, String embossedName, LocalDate expiry,
                             String activeStatus) implements Serializable {
    }

    /** List row of COCRDLIC: the three columns the list shows. */
    public record CardRow(String cardNum, long accountId, String activeStatus) implements Serializable {
    }

    /**
     * Browse filter. {@code account} and {@code card} are null when not applied; {@code scope} is null
     * for an unrestricted (ADMIN) user, otherwise the linked accounts (C-SCOPE).
     */
    public record Filter(Long account, String card, Set<Long> scope) {
    }

    private static final String ROW_COLUMNS = "card_num, account_id, active_status";
    private static final String CARD_COLUMNS = "card_num, account_id, cvv_cd, embossed_name, expiration_date, active_status";

    private final JdbcClient jdbc;

    public CardRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Up to {@code limit} matching cards from {@code key} upward (inclusive or exclusive); null key = file start. */
    public List<CardRow> forward(Filter f, String key, boolean inclusive, int limit) {
        return browse(f, key, inclusive ? ">=" : ">", "ASC", limit);
    }

    /** Up to {@code limit} matching cards below {@code key}, nearest first (descending card number). */
    public List<CardRow> backward(Filter f, String key, int limit) {
        return browse(f, key, "<", "DESC", limit);
    }

    private List<CardRow> browse(Filter f, String key, String op, String order, int limit) {
        if (f.scope() != null && f.scope().isEmpty()) {
            return List.of();
        }
        StringBuilder sql = new StringBuilder("SELECT TOP (" + limit + ") " + ROW_COLUMNS + " FROM card WHERE 1 = 1");
        List<String> names = new ArrayList<>();
        List<Object> values = new ArrayList<>();
        if (key != null) {
            sql.append(" AND card_num ").append(op).append(" :key");
            names.add("key");
            values.add(key);
        }
        if (f.account() != null) {
            sql.append(" AND account_id = :acct");
            names.add("acct");
            values.add(f.account());
        }
        if (f.card() != null) {
            sql.append(" AND card_num = :card");
            names.add("card");
            values.add(f.card());
        }
        if (f.scope() != null) {
            sql.append(" AND account_id IN (:scope)");
            names.add("scope");
            values.add(List.copyOf(f.scope()));
        }
        sql.append(" ORDER BY card_num ").append(order);
        JdbcClient.StatementSpec spec = jdbc.sql(sql.toString());
        for (int i = 0; i < names.size(); i++) {
            spec = spec.param(names.get(i), values.get(i));
        }
        return spec.query((rs, i) -> new CardRow(rs.getString(1), rs.getLong(2), rs.getString(3))).list();
    }

    public Optional<CardRecord> find(String cardNum) {
        return jdbc.sql("SELECT " + CARD_COLUMNS + " FROM card WHERE card_num = ?").param(cardNum)
            .query((rs, i) -> record(rs)).optional();
    }

    /** READ UPDATE: the row with an update lock held until the surrounding transaction ends. */
    public Optional<CardRecord> findForUpdate(String cardNum) {
        return jdbc.sql("SELECT " + CARD_COLUMNS + " FROM card WITH (UPDLOCK, ROWLOCK) WHERE card_num = ?").param(cardNum)
            .query((rs, i) -> record(rs)).optional();
    }

    /** Rewrites only the editable columns; CVV and account id are never written (Q-COCRDUPC-01, -02, -03). */
    public int updateDetails(String cardNum, String embossedName, String activeStatus, LocalDate expiry) {
        return jdbc.sql("UPDATE card SET embossed_name = ?, active_status = ?, expiration_date = ? WHERE card_num = ?")
            .params(embossedName, activeStatus, expiry, cardNum).update();
    }

    private static CardRecord record(java.sql.ResultSet rs) throws java.sql.SQLException {
        java.sql.Date d = rs.getDate(5);
        return new CardRecord(rs.getString(1), rs.getLong(2), rs.getString(3), rs.getString(4),
            d == null ? null : d.toLocalDate(), rs.getString(6));
    }
}
