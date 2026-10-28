package com.carddemo.transaction.internal;

import com.carddemo.transaction.api.NewTransaction;
import com.carddemo.transaction.api.TransactionLedger;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
class JdbcTransactionLedger implements TransactionLedger {

    static final String COUNTER = "online_tran_id";

    private final JdbcClient jdbc;

    JdbcTransactionLedger(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public String nextOnlineTransactionId() {
        // The UPDATE takes the counter row's exclusive lock first; the max lookup then runs while
        // every other online writer waits on that lock. Batch IDs that are not all digits (INTCALC
        // uses a date prefix) are ignored, as are non-numeric keys from any other source.
        long next = jdbc.sql("""
                SET NOCOUNT ON;
                UPDATE id_counter SET value = value WHERE name = :name;
                DECLARE @top BIGINT = COALESCE((SELECT TOP 1 CAST(tran_id AS BIGINT) FROM card_transaction
                    WHERE tran_id NOT LIKE '%[^0-9]%' ORDER BY tran_id DESC), 0);
                UPDATE id_counter SET value = (CASE WHEN value > @top THEN value ELSE @top END) + 1
                    OUTPUT inserted.value WHERE name = :name;
                """)
            .param("name", COUNTER).query(Long.class).single();
        return String.format("%016d", next);
    }

    @Override
    public void insert(NewTransaction t) {
        jdbc.sql("""
                INSERT INTO card_transaction (tran_id, type_cd, cat_cd, source, description, amount, merchant_id,
                    merchant_name, merchant_city, merchant_zip, card_num, orig_ts_text, orig_ts, proc_ts_text, proc_ts)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)
            .params(t.tranId(), t.typeCd(), t.catCd(), t.source(), t.description(), t.amount(), t.merchantId(),
                t.merchantName(), t.merchantCity(), t.merchantZip(), t.cardNum(), t.origTsText(), t.origTs(),
                t.procTsText(), t.procTs())
            .update();
    }
}
