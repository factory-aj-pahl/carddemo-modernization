package com.carddemo.test;

import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Boot configuration for module tests: scans every {@code com.carddemo} component on the test
 * classpath (the module under test and its dependencies) against the migrated test database.
 */
@SpringBootApplication(scanBasePackages = "com.carddemo")
public class TestApplication {
}
