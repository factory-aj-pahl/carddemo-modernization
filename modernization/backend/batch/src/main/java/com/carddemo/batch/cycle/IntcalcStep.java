package com.carddemo.batch.cycle;

import com.carddemo.common.batch.JobLog;
import com.carddemo.cycle.api.InterestCalculation;
import java.time.LocalDate;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.StepExecutionListener;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.batch.item.ItemStreamReader;
import org.springframework.batch.item.ItemWriter;
import org.springframework.stereotype.Component;

/**
 * The CBACT04C main loop as a chunk step with commit interval 1: each account's interest rows, its account
 * update, and its month marker commit together (B-UOW). The reader walks the accounts that have category
 * balances and no interest for the month yet, so a restart after an abend skips the committed accounts
 * (Q-CBACT04C-08). One job runs per process.
 */
@Component
class IntcalcStep implements ItemStreamReader<Long>, ItemWriter<Long>, StepExecutionListener {

    private static final String LAST_ACCOUNT = "intcalc.lastAccount";

    private final InterestCalculation interest;
    private final JobLog log;

    private LocalDate businessDate;
    private String month;
    private long lastAccount;
    private long lastSuffix;

    IntcalcStep(InterestCalculation interest, JobLog log) {
        this.interest = interest;
        this.log = log;
    }

    @Override
    public void beforeStep(StepExecution se) {
        businessDate = LocalDate.parse(se.getJobParameters().getString("businessDate"));
        month = InterestCalculation.interestMonth(businessDate);
        log.display("START OF EXECUTION OF PROGRAM CBACT04C");
    }

    @Override
    public void open(ExecutionContext ctx) {
        lastAccount = ctx.getLong(LAST_ACCOUNT, 0);
        lastSuffix = interest.lastSuffix(InterestCalculation.idPrefix(businessDate));
    }

    @Override
    public Long read() {
        Long next = interest.nextAccount(month, lastAccount).orElse(null);
        if (next != null) {
            lastAccount = next;
        }
        return next;
    }

    @Override
    public void write(Chunk<? extends Long> chunk) {
        for (Long accountId : chunk) {
            lastSuffix = interest.chargeAccount(accountId, businessDate, lastSuffix);
        }
    }

    @Override
    public void update(ExecutionContext ctx) {
        ctx.putLong(LAST_ACCOUNT, lastAccount);
    }

    @Override
    public ExitStatus afterStep(StepExecution se) {
        if (se.getStatus() != BatchStatus.COMPLETED) {
            AbendListener.report(se, log);
            return ExitStatus.FAILED;
        }
        log.display("END OF EXECUTION OF PROGRAM CBACT04C");
        return ExitStatus.COMPLETED;
    }
}
