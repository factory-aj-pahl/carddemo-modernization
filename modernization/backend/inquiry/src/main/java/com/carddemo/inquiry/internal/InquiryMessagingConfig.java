package com.carddemo.inquiry.internal;

import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Declares the inquiry queues when the broker connection opens. */
@Configuration(proxyBeanMethods = false)
class InquiryMessagingConfig {

    /**
     * Q-CODATE01-07, Q-COACCT01-08: a request whose processing fails 3 deliveries goes to the dead-letter queue
     * instead of blocking the queue.
     */
    static final int DELIVERY_LIMIT = 3;
    /** Q-CODATE01-14: bounded reply queues; a full queue rejects new replies instead of growing without limit. */
    static final int REPLY_QUEUE_LIMIT = 10_000;

    @Bean
    Declarables inquiryDeclarables(InquiryQueues q) {
        return new Declarables(
            QueueBuilder.durable(q.dateRequest()).quorum().deliveryLimit(DELIVERY_LIMIT)
                .deadLetterExchange("").deadLetterRoutingKey(q.deadLetter()).build(),
            QueueBuilder.durable(q.dateReply()).maxLength(REPLY_QUEUE_LIMIT).overflow(QueueBuilder.Overflow.rejectPublish).build(),
            QueueBuilder.durable(q.accountRequest()).quorum().deliveryLimit(DELIVERY_LIMIT)
                .deadLetterExchange("").deadLetterRoutingKey(q.deadLetter()).build(),
            QueueBuilder.durable(q.accountReply()).maxLength(REPLY_QUEUE_LIMIT).overflow(QueueBuilder.Overflow.rejectPublish).build(),
            QueueBuilder.durable(q.error()).build(),
            QueueBuilder.durable(q.deadLetter()).build());
    }
}
