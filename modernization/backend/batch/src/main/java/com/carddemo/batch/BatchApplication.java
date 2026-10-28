package com.carddemo.batch;

import com.carddemo.iam.internal.JdbcAccountScope;
import com.carddemo.iam.internal.JdbcUserRoles;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;

/**
 * The batch jar: one command per process, run by Airflow tasks (or by hand), exit code as the job's
 * return code. Only the contexts batch jobs use are scanned, and their web controllers are left out; of
 * IAM only the account scope and the user-role lookup are needed (TRANREPT user requests, D-REPORT).
 */
@SpringBootConfiguration
@EnableAutoConfiguration
@ComponentScan(basePackages = {"com.carddemo.common", "com.carddemo.account", "com.carddemo.card",
    "com.carddemo.transaction", "com.carddemo.refdata", "com.carddemo.reporting", "com.carddemo.cycle", "com.carddemo.batch"},
    excludeFilters = @ComponentScan.Filter(type = FilterType.REGEX, pattern = "com\\.carddemo\\..*\\.web\\..*"))
@Import({JdbcAccountScope.class, JdbcUserRoles.class})
public class BatchApplication {

    public static void main(String[] args) {
        int rc;
        try (ConfigurableApplicationContext ctx = SpringApplication.run(BatchApplication.class, args)) {
            rc = ctx.getBean(BatchCommandLine.class).execute(args);
        }
        System.exit(rc);
    }
}
