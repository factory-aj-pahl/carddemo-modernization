package com.carddemo.test;

import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Every test application context starts from a freshly cleaned and migrated test database. */
@Configuration(proxyBeanMethods = false)
public class TestDatabaseConfig {

    @Bean
    public FlywayMigrationStrategy cleanMigrate() {
        return flyway -> {
            flyway.clean();
            flyway.migrate();
        };
    }
}
