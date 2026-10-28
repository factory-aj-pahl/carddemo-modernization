package com.carddemo.batch.posting;

import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * JOB-POSTTRAN STEP15 (CBTRN02C). Commit interval 1: each daily record, its reject or posting, and the
 * step's restart position commit together (B-UOW, Q-CBTRN02C-07).
 */
@Configuration(proxyBeanMethods = false)
public class PosttranJobConfig {

    public static final String JOB = "POSTTRAN";
    public static final String COMPLETED_WITH_REJECTS = "COMPLETED WITH REJECTS";

    @Bean(JOB)
    Job posttranJob(JobRepository repository, Step step15) {
        return new JobBuilder(JOB, repository).start(step15).build();
    }

    @Bean
    Step step15(JobRepository repository, PlatformTransactionManager tm, PosttranStep step) {
        return new StepBuilder("STEP15", repository).<String, String>chunk(1, tm)
            .reader(step).writer(step).listener(step).stream(step)
            .build();
    }
}
