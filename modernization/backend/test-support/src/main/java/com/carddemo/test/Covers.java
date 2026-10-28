package com.carddemo.test;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Rule, scenario, and ledger IDs a test verifies (for example {@code "BR-COBIL00C-03"},
 * {@code "TS-COBIL00C-05"}, {@code "Q-COBIL00C-01"}). {@code verification/trace.py} joins these tags
 * with the JUnit results to build the traceability matrix. Tag only what the test's assertions check.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface Covers {
    String[] value();
}
