package com.carddemo.transaction.web;

import com.carddemo.card.api.CardDirectory;
import com.carddemo.common.security.AccountScope;
import com.carddemo.common.security.CurrentUser;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Plain reads of the transaction table for the online screens (no locks, Q-COTRN01C-01), limited to
 * the cards of the user's linked accounts for a USER (C-SCOPE, Q-COTRN00C-08). It lives with the screens
 * because the batch jar scans transaction.internal without the IAM account scope.
 */
@Component
public class TransactionReader {

    /** Keyset bound of a list page (Q-COTRN00C-10). */
    public enum Bound { FROM_START, AT_OR_AFTER, AFTER, BEFORE }

    /** One CVTRA05Y record as the screens need it. */
    public record TransactionRow(String tranId, String typeCd, int catCd, String source, String description,
                                 BigDecimal amount, long merchantId, String merchantName, String merchantCity,
                                 String merchantZip, String cardNum, String origTsText, String procTsText) {
    }

    /** The cards a user may see; {@code null} cards means unrestricted (ADMIN). */
    public record Scope(List<String> cards) {
        static final Scope ALL = new Scope(null);

        boolean unrestricted() {
            return cards == null;
        }
    }

    private static final String COLUMNS = "tran_id, type_cd, cat_cd, source, description, amount, merchant_id, "
        + "merchant_name, merchant_city, merchant_zip, card_num, orig_ts_text, proc_ts_text";

    private final JdbcClient jdbc;
    private final CardDirectory cards;
    private final AccountScope accounts;

    TransactionReader(JdbcClient jdbc, CardDirectory cards, AccountScope accounts) {
        this.jdbc = jdbc;
        this.cards = cards;
        this.accounts = accounts;
    }

    public Scope scopeOf(CurrentUser user) {
        if (user.isAdmin()) {
            return Scope.ALL;
        }
        return new Scope(cards.cardsOfAccounts(accounts.linkedAccounts(user.userId())));
    }

    /** True when the card belongs to an account the user may see. */
    public boolean cardInScope(CurrentUser user, String cardNum) {
        if (user.isAdmin()) {
            return true;
        }
        return cards.findXref(cardNum).map(x -> accounts.canAccess(user, x.accountId())).orElse(false);
    }

    /**
     * Up to {@code limit} in-scope transactions next to {@code key}: ascending for FROM_START,
     * AT_OR_AFTER, and AFTER; descending (nearest first) for BEFORE.
     */
    public List<TransactionRow> page(Scope scope, String key, Bound bound, int limit) {
        if (!scope.unrestricted() && scope.cards().isEmpty()) {
            return List.of();
        }
        StringBuilder sql = new StringBuilder("SELECT TOP (" + limit + ") " + COLUMNS + " FROM card_transaction WHERE 1 = 1");
        Bound b = key == null ? Bound.FROM_START : bound;
        switch (b) {
            case AT_OR_AFTER -> sql.append(" AND tran_id >= :key");
            case AFTER -> sql.append(" AND tran_id > :key");
            case BEFORE -> sql.append(" AND tran_id < :key");
            case FROM_START -> {
            }
        }
        if (!scope.unrestricted()) {
            sql.append(" AND card_num IN (:cards)");
        }
        sql.append(b == Bound.BEFORE ? " ORDER BY tran_id DESC" : " ORDER BY tran_id ASC");
        JdbcClient.StatementSpec spec = jdbc.sql(sql.toString());
        if (b != Bound.FROM_START) {
            spec = spec.param("key", key);
        }
        if (!scope.unrestricted()) {
            spec = spec.param("cards", scope.cards());
        }
        return spec.query(TransactionReader::row).list();
    }

    /** Exact key match (BR-COTRN01C-01). */
    public Optional<TransactionRow> find(String tranId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM card_transaction WHERE tran_id = ?")
            .param(tranId).query(TransactionReader::row).optional();
    }

    /** The in-scope transaction with the highest key (Q-COTRN02C-04). */
    public Optional<TransactionRow> highest(Scope scope) {
        if (!scope.unrestricted() && scope.cards().isEmpty()) {
            return Optional.empty();
        }
        String where = scope.unrestricted() ? "" : " WHERE card_num IN (:cards)";
        JdbcClient.StatementSpec spec = jdbc.sql("SELECT TOP (1) " + COLUMNS + " FROM card_transaction" + where
            + " ORDER BY tran_id DESC");
        if (!scope.unrestricted()) {
            spec = spec.param("cards", scope.cards());
        }
        return spec.query(TransactionReader::row).optional();
    }

    private static TransactionRow row(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        return new TransactionRow(rs.getString("tran_id"), rs.getString("type_cd"), rs.getInt("cat_cd"),
            rs.getString("source"), rs.getString("description"), rs.getBigDecimal("amount"), rs.getLong("merchant_id"),
            rs.getString("merchant_name"), rs.getString("merchant_city"), rs.getString("merchant_zip"),
            rs.getString("card_num"), rs.getString("orig_ts_text"), rs.getString("proc_ts_text"));
    }
}
