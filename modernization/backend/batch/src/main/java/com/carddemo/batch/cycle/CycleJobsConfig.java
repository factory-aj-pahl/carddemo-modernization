package com.carddemo.batch.cycle;

import com.carddemo.common.batch.JobLog;
import com.carddemo.common.security.AccountScope;
import com.carddemo.common.security.Role;
import com.carddemo.cycle.api.StatementCycle;
import com.carddemo.cycle.api.TransactionVerification;
import com.carddemo.iam.api.UserRoles;
import com.carddemo.reporting.api.TransactionReport;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * The cycle and report jobs besides POSTTRAN: INTCALC (CBACT04C), CREASTMT (CBSTM03A/B), TRANVERIFY
 * (CBTRN01C), and TRANREPT (CBTRN03C). Parameters are validated by the command line before a job starts.
 */
@Configuration(proxyBeanMethods = false)
public class CycleJobsConfig {

    public static final String INTCALC = "INTCALC";
    public static final String CREASTMT = "CREASTMT";
    public static final String TRANVERIFY = "TRANVERIFY";
    public static final String TRANREPT = "TRANREPT";

    @Bean(INTCALC)
    Job intcalcJob(JobRepository repository, PlatformTransactionManager tm, IntcalcStep step) {
        Step step15 = new StepBuilder("STEP15", repository).<Long, Long>chunk(1, tm)
            .reader(step).writer(step).listener(step).stream(step)
            .build();
        return new JobBuilder(INTCALC, repository).start(step15).build();
    }

    /** STEP040. The tasklet transaction is the statement run's transaction (Q-CREASTMT-04). */
    @Bean(CREASTMT)
    Job creastmtJob(JobRepository repository, PlatformTransactionManager tm, StatementCycle statements, JobLog log) {
        Step step = new StepBuilder("STEP040", repository).tasklet((contribution, chunk) -> {
            Map<String, Object> p = chunk.getStepContext().getJobParameters();
            statements.createStatements(LocalDate.parse((String) p.get("businessDate")), Path.of((String) p.get("stmtFile")),
                Path.of((String) p.get("htmlFile")));
            return RepeatStatus.FINISHED;
        }, tm).listener(new AbendListener(log)).build();
        return new JobBuilder(CREASTMT, repository).start(step).build();
    }

    @Bean(TRANVERIFY)
    Job tranverifyJob(JobRepository repository, PlatformTransactionManager tm, TransactionVerification verification, JobLog log) {
        Step step = new StepBuilder("STEP05", repository).tasklet((contribution, chunk) -> {
            verification.verify(Path.of((String) chunk.getStepContext().getJobParameters().get("dailyFile")));
            return RepeatStatus.FINISHED;
        }, tm).listener(new AbendListener(log)).build();
        return new JobBuilder(TRANVERIFY, repository).start(step).build();
    }

    /**
     * STEP10R. D-REPORT: a USER request covers the accounts linked to the user; an ADMIN request, or a run
     * without a user, covers every account. An unknown user ID gets no accounts.
     */
    @Bean(TRANREPT)
    Job tranreptJob(JobRepository repository, PlatformTransactionManager tm, TransactionReport report, UserRoles roles,
            AccountScope scope, JobLog log) {
        Step step = new StepBuilder("STEP10R", repository).tasklet((contribution, chunk) -> {
            Map<String, Object> p = chunk.getStepContext().getJobParameters();
            String userId = (String) p.get("userId");
            Optional<Set<Long>> accounts = Optional.empty();
            if (userId != null && !userId.isEmpty()) {
                Optional<Role> role = roles.role(userId);
                if (role.isEmpty()) {
                    accounts = Optional.of(Set.of());
                } else if (role.get() == Role.USER) {
                    accounts = Optional.of(scope.linkedAccounts(userId));
                }
            }
            report.write(LocalDate.parse((String) p.get("startDate")), LocalDate.parse((String) p.get("endDate")), accounts,
                Path.of((String) p.get("reportFile")));
            return RepeatStatus.FINISHED;
        }, tm).listener(new AbendListener(log)).build();
        return new JobBuilder(TRANREPT, repository).start(step).build();
    }
}
