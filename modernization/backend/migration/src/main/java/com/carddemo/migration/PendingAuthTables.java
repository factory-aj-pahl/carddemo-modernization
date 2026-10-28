package com.carddemo.migration;

import com.carddemo.migration.PendingAuthSegments.Detail;
import com.carddemo.migration.PendingAuthSegments.Summary;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.SqlParameterValue;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;

/** Rows of {@code pending_auth_summary} and {@code pending_auth_detail} as segment records. */
public final class PendingAuthTables {

    public static final String SUMMARY_COLUMNS = "account_id, customer_id, auth_status, account_status, credit_limit, cash_limit, "
        + "credit_balance, cash_balance, approved_count, declined_count, approved_amt, declined_amt, legacy_status, legacy_filler, legacy_rba";
    public static final String DETAIL_COLUMNS = "account_id, auth_ts, auth_orig_date, auth_orig_time, card_num, auth_type, "
        + "card_expiry_date, message_type, message_source, auth_id_code, auth_resp_code, auth_resp_reason, processing_code, "
        + "transaction_amt, approved_amt, merchant_category_code, acqr_country_code, pos_entry_mode, merchant_id, merchant_name, "
        + "merchant_city, merchant_state, merchant_zip, transaction_id, match_status, auth_fraud, fraud_rpt_date, legacy_filler, legacy_rba";
    /** Newest first (Q-ENT-27), the order of the IMS child key. */
    public static final String DETAIL_ORDER = "auth_ts DESC, auth_id DESC";

    public static final RowMapper<Summary> SUMMARY = (rs, i) -> new Summary(rs.getLong("account_id"), rs.getInt("customer_id"),
        rs.getString("auth_status"), rs.getString("account_status"), rs.getBigDecimal("credit_limit"), rs.getBigDecimal("cash_limit"),
        rs.getBigDecimal("credit_balance"), rs.getBigDecimal("cash_balance"), rs.getInt("approved_count"), rs.getInt("declined_count"),
        rs.getBigDecimal("approved_amt"), rs.getBigDecimal("declined_amt"), rs.getBytes("legacy_status"), rs.getBytes("legacy_filler"),
        intOrNull(rs, "legacy_rba"));

    public static final RowMapper<Detail> DETAIL = (rs, i) -> new Detail(rs.getLong("auth_id"), rs.getLong("account_id"),
        rs.getTimestamp("auth_ts").toLocalDateTime(), rs.getString("auth_orig_date"), rs.getString("auth_orig_time"),
        rs.getString("card_num"), rs.getString("auth_type"), rs.getString("card_expiry_date"), rs.getString("message_type"),
        rs.getString("message_source"), rs.getString("auth_id_code"), rs.getString("auth_resp_code"), rs.getString("auth_resp_reason"),
        rs.getString("processing_code"), rs.getBigDecimal("transaction_amt"), rs.getBigDecimal("approved_amt"),
        rs.getString("merchant_category_code"), rs.getString("acqr_country_code"), rs.getString("pos_entry_mode"),
        rs.getString("merchant_id"), rs.getString("merchant_name"), rs.getString("merchant_city"), rs.getString("merchant_state"),
        rs.getString("merchant_zip"), rs.getString("transaction_id"), rs.getString("match_status"), rs.getString("auth_fraud"),
        rs.getDate("fraud_rpt_date") == null ? null : rs.getDate("fraud_rpt_date").toLocalDate(), rs.getBytes("legacy_filler"),
        intOrNull(rs, "legacy_rba"));

    private PendingAuthTables() {
    }

    private static Integer intOrNull(ResultSet rs, String col) throws SQLException {
        int v = rs.getInt(col);
        return rs.wasNull() ? null : v;
    }

    public static void insertSummary(JdbcTemplate jdbc, Summary s) {
        jdbc.update("INSERT INTO pending_auth_summary (" + SUMMARY_COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            s.accountId(), s.customerId(), s.authStatus(), s.accountStatus(), s.creditLimit(), s.cashLimit(), s.creditBalance(),
            s.cashBalance(), s.approvedCount(), s.declinedCount(), s.approvedAmt(), s.declinedAmt(),
            new SqlParameterValue(Types.VARBINARY, s.legacyStatus()), new SqlParameterValue(Types.VARBINARY, s.legacyFiller()),
            new SqlParameterValue(Types.INTEGER, s.legacyRba()));
    }

    /** Inserts a detail and returns its generated authorization id. */
    public static long insertDetail(JdbcTemplate jdbc, Detail d) {
        KeyHolder key = new GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement("INSERT INTO pending_auth_detail (" + DETAIL_COLUMNS + ") VALUES ("
                + "?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", new String[] {"auth_id"});
            Object[] v = {d.accountId(), Timestamp.valueOf(d.authTs()), d.origDate(), d.origTime(), d.cardNum(), d.authType(),
                d.cardExpiryDate(), d.messageType(), d.messageSource(), d.authIdCode(), d.respCode(), d.respReason(),
                d.processingCode(), d.transactionAmt(), d.approvedAmt(), d.merchantCategoryCode(), d.acqrCountryCode(),
                d.posEntryMode(), d.merchantId(), d.merchantName(), d.merchantCity(), d.merchantState(), d.merchantZip(),
                d.transactionId(), d.matchStatus(), d.authFraud(),
                d.fraudRptDate() == null ? null : java.sql.Date.valueOf(d.fraudRptDate()), d.legacyFiller(), d.legacyRba()};
            for (int i = 0; i < v.length; i++) {
                if (v[i] == null) {
                    ps.setNull(i + 1, i == 26 ? java.sql.Types.DATE : i == 27 ? java.sql.Types.VARBINARY : java.sql.Types.INTEGER);
                } else {
                    ps.setObject(i + 1, v[i]);
                }
            }
            return ps;
        }, key);
        return key.getKey().longValue();
    }

    public static List<Summary> summaries(JdbcTemplate jdbc) {
        return jdbc.query("SELECT * FROM pending_auth_summary ORDER BY account_id", SUMMARY);
    }

    public static List<Detail> details(JdbcTemplate jdbc, long accountId) {
        return jdbc.query("SELECT * FROM pending_auth_detail WHERE account_id = ? ORDER BY " + DETAIL_ORDER, DETAIL, accountId);
    }
}
