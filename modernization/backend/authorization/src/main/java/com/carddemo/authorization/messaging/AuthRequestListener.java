package com.carddemo.authorization.messaging;

import com.carddemo.authorization.internal.AuthErrorLog;
import com.carddemo.authorization.internal.AuthErrorLog.Level;
import com.carddemo.authorization.internal.AuthErrorLog.Subsystem;
import com.carddemo.authorization.internal.AuthReply;
import com.carddemo.authorization.internal.AuthRequest;
import com.carddemo.authorization.internal.AuthorizationEngine;
import com.carddemo.authorization.internal.AuthorizationEngine.AuthorizationFailure;
import com.carddemo.common.mask.Masking;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

/**
 * COPAUA0C as a long-lived RabbitMQ consumer (Q-COPAUA0C-15). Per message: parse, decide and commit, then publish
 * the reply, then acknowledge (Q-COPAUA0C-06). Any number of comma-separated fields is accepted, as the COBOL
 * UNSTRING does (BR-COPAUA0C-03), and every byte is taken as received. Only a message over the 500-byte request
 * buffer cannot be read (the MQGET failure, M003); it is logged and dead-lettered, as is a request whose transaction
 * fails.
 */
@Component
class AuthRequestListener {

    /** W01-GET-BUFFER is 500 bytes. */
    static final int MAX_REQUEST_BYTES = 500;
    /** One character per byte, so any byte sequence decodes and the reply echoes the request's bytes. */
    static final Charset BYTES = StandardCharsets.ISO_8859_1;

    private final RabbitTemplate rabbit;
    private final AuthorizationEngine engine;
    private final AuthErrorLog errors;
    private final AuthQueues queues;

    AuthRequestListener(RabbitTemplate rabbit, AuthorizationEngine engine, AuthErrorLog errors, AuthQueues queues) {
        this.rabbit = rabbit;
        this.engine = engine;
        this.errors = errors;
        this.queues = queues;
    }

    @RabbitListener(queues = "#{@authQueues.request()}")
    void onRequest(Message request) {
        MessageProperties in = request.getMessageProperties();
        byte[] body = request.getBody();
        if (body.length > MAX_REQUEST_BYTES) {
            // MQGET into the 500-byte buffer fails (MQRC_TRUNCATED_MSG_FAILED 2080); Q-COPAUA0C-16: subsystem M.
            errors.log("M003", Level.CRITICAL, Subsystem.MQ, "2", "2080", "FAILED TO READ REQUEST MQ", "");
            throw new AmqpRejectAndDontRequeueException("request of " + body.length + " bytes exceeds " + MAX_REQUEST_BYTES);
        }
        AuthRequest rq = AuthRequest.parse(new String(body, BYTES));
        AuthReply reply;
        try {
            reply = engine.authorize(rq);
        } catch (AuthorizationFailure e) {
            throw new AmqpRejectAndDontRequeueException(e.getMessage(), e);
        }
        String replyTo = replyQueue(in, queues.reply());
        try {
            if (!ReplyQueueCheck.exists(rabbit, replyTo)) {
                throw new AmqpException("reply queue " + replyTo + " does not exist");
            }
            rabbit.send("", replyTo, new Message(reply.text().getBytes(BYTES), replyProperties(in)));
        } catch (AmqpException e) {
            // ERR-COPAUA0C-08. The authorization is already committed, so the request is acknowledged, not redelivered.
            errors.log("M004", Level.CRITICAL, Subsystem.MQ, "2", "", "FAILED TO PUT ON REPLY MQ", Masking.card(rq.cardNum()));
        }
    }

    /** The request's reply_to, else the fixed reply queue. */
    static String replyQueue(MessageProperties request, String fallback) {
        String replyTo = request.getReplyTo();
        return replyTo == null || replyTo.isBlank() ? fallback : replyTo;
    }

    /** BR-COPAUA0C-12 on RabbitMQ: type reply, correlation_id = the request's message_id (else its correlation_id). */
    static MessageProperties replyProperties(MessageProperties request) {
        MessageProperties out = new MessageProperties();
        out.setContentType(MessageProperties.CONTENT_TYPE_TEXT_PLAIN);
        out.setContentEncoding(BYTES.name());
        out.setMessageId(UUID.randomUUID().toString());
        out.setType("reply");
        out.setDeliveryMode(MessageDeliveryMode.NON_PERSISTENT);
        out.setExpiration("5000");
        String correlation = request.getMessageId() != null ? request.getMessageId() : request.getCorrelationId();
        if (correlation != null) {
            out.setCorrelationId(correlation);
        }
        return out;
    }
}
