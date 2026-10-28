package com.carddemo.authorization.internal;

import com.carddemo.migration.PendingAuthSegments.Detail;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * COPAUS2C: records a fraud report ('F') or its removal ('R') in {@code auth_fraud_report} (CARDDEMO.AUTHFRDS).
 * Inserts a row with every authorization field; when the row exists, only the flag and report date change.
 * Never commits: it runs inside the caller's transaction (BR-COPAUS2C-07).
 */
@Component
public class FraudReports {

    /** WS-FRD-UPDATE-STATUS 'S' / 'F' and WS-FRD-ACT-MSG. */
    public record Result(boolean success, String message) {
    }

    public static final String ADD_SUCCESS = "ADD SUCCESS";
    public static final String UPDT_SUCCESS = "UPDT SUCCESS";
    static final String SYSTEM_ERROR = " SYSTEM ERROR DB2: CODE:";
    static final String UPDT_ERROR = " UPDT ERROR DB2: CODE:";

    private final JdbcTemplate jdbc;

    public FraudReports(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * @param d          the authorization; its stored insert timestamp is AUTH_TS (Q-COPAUS2C-01) and its unique key is
     *                   part of the row key (Q-COPAUS2C-03)
     * @param reportDate the one report date written to this row and to the authorization (Q-ENT-28)
     */
    public Result record(long accountId, long customerId, Detail d, String action, LocalDate reportDate) {
        try {
            jdbc.update("INSERT INTO auth_fraud_report (card_num, auth_ts, auth_id, auth_type, card_expiry_date, message_type, "
                + "message_source, auth_id_code, auth_resp_code, auth_resp_reason, processing_code, transaction_amt, approved_amt, "
                + "merchant_category_code, acqr_country_code, pos_entry_mode, merchant_id, merchant_name, merchant_city, "
                + "merchant_state, merchant_zip, transaction_id, match_status, auth_fraud, fraud_rpt_date, acct_id, cust_id) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                d.cardNum(), Timestamp.valueOf(d.authTs()), d.authId(), d.authType(), d.cardExpiryDate(), d.messageType(),
                d.messageSource(), d.authIdCode(), d.respCode(), d.respReason(), d.processingCode(), d.transactionAmt(),
                d.approvedAmt(), d.merchantCategoryCode(), d.acqrCountryCode(), posEntryMode(d.posEntryMode()), d.merchantId(),
                d.merchantName() == null ? null : d.merchantName().strip(), d.merchantCity(), d.merchantState(), d.merchantZip(),
                d.transactionId(), d.matchStatus(), action, java.sql.Date.valueOf(reportDate), accountId, customerId);
            return new Result(true, ADD_SUCCESS);
        } catch (DuplicateKeyException duplicate) {
            return update(d, action, reportDate);
        } catch (DataAccessException e) {
            return new Result(false, systemError(e));
        }
    }

    /** FRAUD-UPDATE: flag and report date of the existing row; a missing row is SQLCODE +100. */
    private Result update(Detail d, String action, LocalDate reportDate) {
        try {
            int n = jdbc.update("UPDATE auth_fraud_report SET auth_fraud = ?, fraud_rpt_date = ? WHERE card_num = ? AND auth_ts = ? "
                + "AND auth_id = ?", action, java.sql.Date.valueOf(reportDate), d.cardNum(), Timestamp.valueOf(d.authTs()),
                d.authId());
            return n == 1 ? new Result(true, UPDT_SUCCESS) : new Result(false, cut(UPDT_ERROR + "+000100, STATE: 02000"));
        } catch (DataAccessException e) {
            return new Result(false, cut(UPDT_ERROR + sqlCode(e) + ", STATE: " + sqlState(e)));
        }
    }

    /** ERR-COPAUS2C-01 text for a failure; also shown when the call itself fails (Q-COPAUS1C-01). */
    public static String systemError(Throwable e) {
        return cut(SYSTEM_ERROR + sqlCode(e) + ", STATE: " + sqlState(e));
    }

    /**
     * SQLCODE as {@code +9(06)}. SQL Server reports positive error numbers; they are shown with the Db2 error sign, so
     * a failure reads {@code -000208}. A failure without a database error shows {@code +000000}.
     */
    static String sqlCode(Throwable e) {
        SQLException sql = sqlException(e);
        int code = sql == null ? 0 : -Math.abs(sql.getErrorCode());
        return (code < 0 ? "-" : "+") + String.format("%06d", Math.abs(code));
    }

    /** Q-COPAUS2C-04: the 5-character SQLSTATE as text. */
    static String sqlState(Throwable e) {
        SQLException sql = sqlException(e);
        return sql == null || sql.getSQLState() == null ? "" : sql.getSQLState();
    }

    private static SQLException sqlException(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof SQLException sql) {
                return sql;
            }
        }
        return null;
    }

    /** POS_ENTRY_MODE is SMALLINT; the detail holds the 2-digit text. */
    private static Integer posEntryMode(String s) {
        return s != null && !s.isBlank() && s.strip().chars().allMatch(Character::isDigit) ? Integer.valueOf(s.strip()) : null;
    }

    /** WS-FRD-ACT-MSG is 50 characters. */
    private static String cut(String s) {
        return s.length() > 50 ? s.substring(0, 50) : s;
    }
}
