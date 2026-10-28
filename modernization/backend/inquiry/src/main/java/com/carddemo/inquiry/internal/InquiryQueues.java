package com.carddemo.inquiry.internal;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Broker queue names. The defaults are the COBOL names (Q-CODATE01-01, Q-COACCT01-01); a suffix gives a
 * test run private queues on a shared broker. The request queues come from the trigger definitions,
 * which are not in the repository; they are named after the reply queues.
 */
@Component
public class InquiryQueues {

    public static final String DATE_REQUEST = "CARD.DEMO.REQUEST.DATE";
    public static final String DATE_REPLY = "CARD.DEMO.REPLY.DATE";
    public static final String ACCOUNT_REQUEST = "CARD.DEMO.REQUEST.ACCT";
    public static final String ACCOUNT_REPLY = "CARD.DEMO.REPLY.ACCT";
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

    public String accountRequest() {
        return ACCOUNT_REQUEST + suffix;
    }

    public String accountReply() {
        return ACCOUNT_REPLY + suffix;
    }

    public String error() {
        return ERROR + suffix;
    }

    public String deadLetter() {
        return DEAD_LETTER + suffix;
    }
}
