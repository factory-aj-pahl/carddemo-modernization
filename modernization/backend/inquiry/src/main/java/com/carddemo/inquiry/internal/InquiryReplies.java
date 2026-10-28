package com.carddemo.inquiry.internal;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.ReturnListener;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

/**
 * Publishes inquiry replies and error records and confirms that the broker took them (the MQPUT outcome):
 * a reply that a full reply queue rejects, or that no queue receives, is a failed put and is reported on the
 * error queue (ERR-COACCT01-07, ERR-CODATE01-06). A failure to write the error record itself is thrown, so the
 * request is redelivered and finally dead-lettered rather than lost (ERR-COACCT01-08, ERR-CODATE01-07).
 */
@Component
class InquiryReplies {

    private static final Logger log = LoggerFactory.getLogger(InquiryReplies.class);
    private static final long CONFIRM_TIMEOUT_MS = 5_000;

    enum PutResult { OK, QUEUE_FULL, NO_SUCH_QUEUE }

    private final RabbitTemplate rabbit;
    private final InquiryQueues queues;

    InquiryReplies(RabbitTemplate rabbit, InquiryQueues queues) {
        this.rabbit = rabbit;
        this.queues = queues;
    }

    /** Q-COACCT01-02, Q-CODATE01-04: the request's reply_to, else the fixed reply queue. */
    static String replyQueue(MessageProperties request, String fallback) {
        String replyTo = request.getReplyTo();
        return replyTo == null || replyTo.isBlank() ? fallback : replyTo;
    }

    /** Q-COACCT01-03, Q-CODATE01-05: correlation_id = the request's message_id (its correlation_id if it has none). */
    static String correlation(MessageProperties request) {
        return request.getMessageId() != null ? request.getMessageId() : request.getCorrelationId();
    }

    /**
     * Sends a 1000-byte text reply of the given type ({@code reply}, or {@code error} for an error reply) to the
     * requester; a failed put is reported on the error queue with the reply queue's name.
     */
    void reply(MessageProperties request, String replyQueue, String text, String type) {
        PutResult r = put(replyQueue, properties(request, type), text);
        if (r != PutResult.OK) {
            int reason = r == PutResult.QUEUE_FULL ? ErrorRecord.Q_FULL : ErrorRecord.UNKNOWN_OBJECT_NAME;
            reportError(ErrorRecord.broker("MQPUT ERR", reason, replyQueue), request);
        }
    }

    /** Writes the error record to the error queue; throws when the error queue does not take it. */
    void reportError(ErrorRecord record, MessageProperties request) {
        PutResult r = put(queues.error(), properties(request, "error"), record.text());
        if (r != PutResult.OK) {
            int reason = r == PutResult.QUEUE_FULL ? ErrorRecord.Q_FULL : ErrorRecord.UNKNOWN_OBJECT_NAME;
            String display = ErrorRecord.broker("MQPUT ERR", reason, queues.error()).text().stripTrailing();
            log.error("{} (error record not written: {})", display, record.text().stripTrailing());
            throw new AmqpException("error queue " + queues.error() + " did not accept the error record: " + r);
        }
    }

    static AMQP.BasicProperties properties(MessageProperties request, String type) {
        return new AMQP.BasicProperties.Builder()
            .contentType(MessageProperties.CONTENT_TYPE_TEXT_PLAIN)
            .contentEncoding(StandardCharsets.US_ASCII.name())
            .messageId(UUID.randomUUID().toString())
            .correlationId(correlation(request))
            .type(type)
            .deliveryMode(2)
            .build();
    }

    private PutResult put(String queue, AMQP.BasicProperties props, String text) {
        byte[] body = text.getBytes(StandardCharsets.US_ASCII);
        return rabbit.execute(channel -> {
            channel.confirmSelect();
            AtomicBoolean returned = new AtomicBoolean();
            ReturnListener listener = channel.addReturnListener(ret -> returned.set(true));
            try {
                channel.basicPublish("", queue, true, props, body);
                // The broker sends basic.return for an unroutable mandatory message before its ack.
                boolean acked = channel.waitForConfirms(CONFIRM_TIMEOUT_MS);
                if (returned.get()) {
                    return PutResult.NO_SUCH_QUEUE;
                }
                return acked ? PutResult.OK : PutResult.QUEUE_FULL;
            } catch (TimeoutException e) {
                throw new IOException("no publisher confirm from the broker within " + CONFIRM_TIMEOUT_MS + " ms", e);
            } finally {
                channel.removeReturnListener(listener);
            }
        });
    }
}
