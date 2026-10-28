package com.carddemo.batch.backup;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * BACKUP and RESTORE (B-CYCLE, Q-TRANBKP-01, Q-ACCTFILE-02): a consistent snapshot, taken before POSTTRAN, of
 * everything the cycle changes (account balances and cycle totals, the set of transactions and their statement
 * marks, category balances, rejects, statement runs, interest markers, and the batch job history). RESTORE puts
 * the snapshot back, so the cycle can be rerun for the same date. Like the load and export commands this is an
 * operational tool over the whole schema, not a bounded context.
 */
@Component
public class CycleBackupService {

    private final JdbcClient jdbc;
    private final Clock clock;

    CycleBackupService(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public record Snapshot(long backupId, LocalDate businessDate, int accounts, int transactions, int categoryBalances) {
    }

    /** One SERIALIZABLE transaction, so no posting or online change can fall between the copied tables. */
    @Transactional(isolation = Isolation.SERIALIZABLE)
    public Snapshot backup(LocalDate businessDate) {
        long id = jdbc.sql("""
                INSERT INTO cycle_backup (business_date, created_at, max_reject_id, max_statement_run_id, max_job_instance_id,
                    account_rows, transaction_rows, category_rows)
                OUTPUT INSERTED.backup_id
                SELECT ?, ?, COALESCE((SELECT MAX(reject_id) FROM posting_reject), 0),
                    COALESCE((SELECT MAX(statement_run_id) FROM statement_run), 0),
                    COALESCE((SELECT MAX(JOB_INSTANCE_ID) FROM BATCH_JOB_INSTANCE), 0), 0, 0, 0
                """)
            .params(businessDate, LocalDateTime.now(clock)).query(Long.class).single();
        int accounts = jdbc.sql("""
                INSERT INTO cycle_backup_account (backup_id, account_id, active_status, current_balance, credit_limit,
                    cash_credit_limit, open_date, expiration_date, reissue_date, curr_cycle_credit, curr_cycle_debit, addr_zip, group_id)
                SELECT ?, account_id, active_status, current_balance, credit_limit, cash_credit_limit, open_date, expiration_date,
                    reissue_date, curr_cycle_credit, curr_cycle_debit, addr_zip, group_id FROM account
                """).param(id).update();
        int transactions = jdbc.sql("""
                INSERT INTO cycle_backup_transaction (backup_id, tran_id, statement_run_id)
                SELECT ?, tran_id, statement_run_id FROM card_transaction
                """).param(id).update();
        int categories = jdbc.sql("""
                INSERT INTO cycle_backup_category (backup_id, account_id, type_cd, cat_cd, balance, legacy_filler)
                SELECT ?, account_id, type_cd, cat_cd, balance, legacy_filler FROM tran_cat_balance
                """).param(id).update();
        jdbc.sql("""
                INSERT INTO cycle_backup_interest (backup_id, interest_month, account_id, business_date, interest_rows,
                    total_interest, processed_at)
                SELECT ?, interest_month, account_id, business_date, interest_rows, total_interest, processed_at FROM interest_account
                """).param(id).update();
        jdbc.sql("UPDATE cycle_backup SET account_rows = ?, transaction_rows = ?, category_rows = ? WHERE backup_id = ?")
            .params(accounts, transactions, categories, id).update();
        return new Snapshot(id, businessDate, accounts, transactions, categories);
    }

    /** The newest backup taken for a business date. */
    public Optional<Long> latestFor(LocalDate businessDate) {
        return jdbc.sql("SELECT MAX(backup_id) FROM cycle_backup WHERE business_date = ?").param(businessDate)
            .query(Long.class).optional();
    }

    /**
     * Puts a snapshot back in one transaction. Rows added after the backup (posted, interest, or online
     * transactions, rejects, statement runs, batch job runs) are removed; changed rows get their saved values.
     */
    @Transactional(isolation = Isolation.SERIALIZABLE)
    public Optional<Snapshot> restore(long backupId) {
        Optional<Snapshot> snap = jdbc.sql("""
                SELECT backup_id, business_date, account_rows, transaction_rows, category_rows FROM cycle_backup WHERE backup_id = ?
                """).param(backupId)
            .query((rs, i) -> new Snapshot(rs.getLong(1), rs.getObject(2, LocalDate.class), rs.getInt(3), rs.getInt(4), rs.getInt(5)))
            .optional();
        if (snap.isEmpty()) {
            return snap;
        }
        jdbc.sql("""
                UPDATE a SET active_status = b.active_status, current_balance = b.current_balance, credit_limit = b.credit_limit,
                    cash_credit_limit = b.cash_credit_limit, open_date = b.open_date, expiration_date = b.expiration_date,
                    reissue_date = b.reissue_date, curr_cycle_credit = b.curr_cycle_credit, curr_cycle_debit = b.curr_cycle_debit,
                    addr_zip = b.addr_zip, group_id = b.group_id
                FROM account a JOIN cycle_backup_account b ON b.account_id = a.account_id AND b.backup_id = ?
                """).param(backupId).update();
        jdbc.sql("""
                DELETE t FROM card_transaction t WHERE NOT EXISTS (
                    SELECT 1 FROM cycle_backup_transaction b WHERE b.backup_id = ? AND b.tran_id = t.tran_id)
                """).param(backupId).update();
        jdbc.sql("""
                UPDATE t SET statement_run_id = b.statement_run_id
                FROM card_transaction t JOIN cycle_backup_transaction b ON b.tran_id = t.tran_id AND b.backup_id = ?
                """).param(backupId).update();
        jdbc.sql("DELETE FROM tran_cat_balance").update();
        jdbc.sql("""
                INSERT INTO tran_cat_balance (account_id, type_cd, cat_cd, balance, legacy_filler)
                SELECT account_id, type_cd, cat_cd, balance, legacy_filler FROM cycle_backup_category WHERE backup_id = ?
                """).param(backupId).update();
        jdbc.sql("DELETE FROM interest_account").update();
        jdbc.sql("""
                INSERT INTO interest_account (interest_month, account_id, business_date, interest_rows, total_interest, processed_at)
                SELECT interest_month, account_id, business_date, interest_rows, total_interest, processed_at
                FROM cycle_backup_interest WHERE backup_id = ?
                """).param(backupId).update();
        jdbc.sql("DELETE FROM posting_reject WHERE reject_id > (SELECT max_reject_id FROM cycle_backup WHERE backup_id = ?)")
            .param(backupId).update();
        jdbc.sql("""
                DELETE FROM statement_run WHERE statement_run_id > (SELECT max_statement_run_id FROM cycle_backup WHERE backup_id = ?)
                """).param(backupId).update();
        forgetJobsAfter(backupId);
        jdbc.sql("UPDATE cycle_backup SET restored_at = ? WHERE backup_id = ?").params(LocalDateTime.now(clock), backupId).update();
        return snap;
    }

    /** Batch runs started after the backup are forgotten, so POSTTRAN, INTCALC, and CREASTMT can run again. */
    private void forgetJobsAfter(long backupId) {
        String instances = "SELECT JOB_INSTANCE_ID FROM BATCH_JOB_INSTANCE WHERE JOB_INSTANCE_ID > "
            + "(SELECT max_job_instance_id FROM cycle_backup WHERE backup_id = ?)";
        String executions = "SELECT JOB_EXECUTION_ID FROM BATCH_JOB_EXECUTION WHERE JOB_INSTANCE_ID IN (" + instances + ")";
        String steps = "SELECT STEP_EXECUTION_ID FROM BATCH_STEP_EXECUTION WHERE JOB_EXECUTION_ID IN (" + executions + ")";
        jdbc.sql("DELETE FROM BATCH_STEP_EXECUTION_CONTEXT WHERE STEP_EXECUTION_ID IN (" + steps + ")").param(backupId).update();
        jdbc.sql("DELETE FROM BATCH_STEP_EXECUTION WHERE JOB_EXECUTION_ID IN (" + executions + ")").param(backupId).update();
        jdbc.sql("DELETE FROM BATCH_JOB_EXECUTION_CONTEXT WHERE JOB_EXECUTION_ID IN (" + executions + ")").param(backupId).update();
        jdbc.sql("DELETE FROM BATCH_JOB_EXECUTION_PARAMS WHERE JOB_EXECUTION_ID IN (" + executions + ")").param(backupId).update();
        jdbc.sql("DELETE FROM BATCH_JOB_EXECUTION WHERE JOB_INSTANCE_ID IN (" + instances + ")").param(backupId).update();
        jdbc.sql("DELETE FROM BATCH_JOB_INSTANCE WHERE JOB_INSTANCE_ID > (SELECT max_job_instance_id FROM cycle_backup WHERE backup_id = ?)")
            .param(backupId).update();
    }
}
