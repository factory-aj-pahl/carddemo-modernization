package com.carddemo.authorization.messaging;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Broker queue names: the module README names (BR-COPAUA0C-01; RabbitMQ has no trigger message, the
 * listener consumes the request queue directly). A suffix gives a test run private queues on a shared broker.
 */
@Component
public class AuthQueues {

    public static final String REQUEST = "AWS.M2.CARDDEMO.PAUTH.REQUEST";
    public static final String REPLY = "AWS.M2.CARDDEMO.PAUTH.REPLY";
    public static final String DEAD_LETTER = "CARD.DEMO.DLQ";

    private final String suffix;

    AuthQueues(@Value("${carddemo.mq.queue-suffix:}") String suffix) {
        this.suffix = suffix;
    }

    public String request() {
        return REQUEST + suffix;
    }

    public String reply() {
        return REPLY + suffix;
    }

    public String deadLetter() {
        return DEAD_LETTER + suffix;
    }
}
