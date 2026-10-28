package com.carddemo.transaction.internal;

import com.carddemo.transaction.api.TransactionQueries;
import com.carddemo.transaction.api.TransactionRecord;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
class JdbcTransactionQueries implements TransactionQueries {

    private static final String COLUMNS = """
            tran_id, type_cd, cat_cd, source, description, amount, merchant_id, merchant_name, merchant_city,
            merchant_zip, card_num, orig_ts_text, proc_ts_text""";

    private final JdbcClient jdbc;

    JdbcTransactionQueries(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private static TransactionRecord row(ResultSet rs, int i) throws SQLException {
        return new TransactionRecord(rs.getString(1), rs.getString(2), rs.getInt(3), rs.getString(4), rs.getString(5),
            rs.getBigDecimal(6), rs.getLong(7), rs.getString(8), rs.getString(9), rs.getString(10), rs.getString(11),
            rs.getString(12), rs.getString(13));
    }

    @Override
    public List<TransactionRecord> processedBetween(LocalDate start, LocalDate end) {
        return jdbc.sql("SELECT " + COLUMNS + """
                 FROM card_transaction
                WHERE proc_ts >= ? AND proc_ts < ?
                ORDER BY card_num, tran_id
                """)
            .params(start.atStartOfDay(), end.plusDays(1).atStartOfDay())
            .query(JdbcTransactionQueries::row).list();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public int coverUncovered(long statementRunId) {
        return jdbc.sql("UPDATE card_transaction SET statement_run_id = ? WHERE statement_run_id IS NULL")
            .param(statementRunId).update();
    }

    @Override
    public List<TransactionRecord> coveredBy(long statementRunId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM card_transaction WHERE statement_run_id = ? ORDER BY card_num, tran_id")
            .param(statementRunId).query(JdbcTransactionQueries::row).list();
    }

    @Override
    public Optional<String> highestIdWithPrefix(String prefix) {
        return jdbc.sql("SELECT MAX(tran_id) FROM card_transaction WHERE tran_id LIKE ?")
            .param(prefix.replace("[", "[[]").replace("%", "[%]").replace("_", "[_]") + "%")
            .query(String.class).optional();
    }
}
