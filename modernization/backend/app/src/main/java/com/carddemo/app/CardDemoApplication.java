package com.carddemo.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** The online API: every bounded context in one Spring Boot service (modular monolith). */
@SpringBootApplication(scanBasePackages = "com.carddemo")
public class CardDemoApplication {

    public static void main(String[] args) {
        SpringApplication.run(CardDemoApplication.class, args);
    }
}
