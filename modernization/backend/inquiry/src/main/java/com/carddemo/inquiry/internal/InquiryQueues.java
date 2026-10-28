package com.carddemo.inquiry.internal;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Broker queue names. The defaults are the COBOL names (Q-CODATE01-01, Q-COACCT01-01); a suffix gives a
 * test run private queues on a shared broker.
 */
@Component
public class InquiryQueues {

    public static final String DATE_REQUEST = "CARD.DEMO.REQUEST.DATE";
    public static final String DATE_REPLY = "CARD.DEMO.REPLY.DATE";
    public static final String ERROR = "CARD.DEMO.ERROR";
    public static final String DEAD_LETTER = "CARD.DEMO.DLQ";

    private final String suffix;

    InquiryQueues(@Value("${carddemo.mq.queue-suffix:}") String suffix) {
        this.suffix = suffix;
    }

    public String dateRequest() {
        return DATE_REQUEST + suffix;
    }

    public String dateReply() {
        return DATE_REPLY + suffix;
    }

    public String error() {
        return ERROR + suffix;
    }

    public String deadLetter() {
        return DEAD_LETTER + suffix;
    }
}
