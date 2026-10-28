package com.carddemo.batch;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;

/**
 * The batch jar: one command per process, run by Airflow tasks (or by hand), exit code as the job's
 * return code. Only the contexts batch jobs use are scanned, and their web controllers are left out.
 */
@SpringBootConfiguration
@EnableAutoConfiguration
@ComponentScan(basePackages = {"com.carddemo.common", "com.carddemo.account", "com.carddemo.card",
    "com.carddemo.transaction", "com.carddemo.cycle", "com.carddemo.dataexchange", "com.carddemo.batch"},
    excludeFilters = @ComponentScan.Filter(type = FilterType.REGEX, pattern = "com\\.carddemo\\..*\\.web\\..*"))
public class BatchApplication {

    public static void main(String[] args) {
        int rc;
        try (ConfigurableApplicationContext ctx = SpringApplication.run(BatchApplication.class, args)) {
            rc = ctx.getBean(BatchCommandLine.class).execute(args);
        }
        System.exit(rc);
    }
}
