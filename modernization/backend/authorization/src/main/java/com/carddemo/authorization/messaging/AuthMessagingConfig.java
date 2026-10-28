package com.carddemo.authorization.messaging;

import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Declares the authorization queues when the broker connection opens (D-MQ). */
@Configuration(proxyBeanMethods = false)
class AuthMessagingConfig {

    /** A request that fails 3 deliveries goes to the dead-letter queue instead of blocking the queue. */
    static final int DELIVERY_LIMIT = 3;
    /** Bounded fixed reply queue; a full queue rejects new replies instead of growing without limit. */
    static final int REPLY_QUEUE_LIMIT = 10_000;

    @Bean
    Declarables authorizationDeclarables(AuthQueues q) {
        return new Declarables(
            QueueBuilder.durable(q.request()).quorum().deliveryLimit(DELIVERY_LIMIT)
                .deadLetterExchange("").deadLetterRoutingKey(q.deadLetter()).build(),
            QueueBuilder.durable(q.reply()).maxLength(REPLY_QUEUE_LIMIT).overflow(QueueBuilder.Overflow.rejectPublish).build(),
            QueueBuilder.durable(q.deadLetter()).build());
    }
}
