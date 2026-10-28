package com.carddemo.authorization.messaging;

import com.carddemo.authorization.internal.AuthErrorLog;
import com.carddemo.authorization.internal.AuthErrorLog.Level;
import com.carddemo.authorization.internal.AuthErrorLog.Subsystem;
import com.carddemo.authorization.internal.AuthReply;
import com.carddemo.authorization.internal.AuthRequest;
import com.carddemo.authorization.internal.AuthorizationEngine;
import com.carddemo.authorization.internal.AuthorizationEngine.AuthorizationFailure;
import com.carddemo.common.mask.Masking;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

/**
 * COPAUA0C as a long-lived RabbitMQ consumer (Q-COPAUA0C-15). Per message: parse, decide and commit, then publish
 * the reply, then acknowledge (Q-COPAUA0C-06). Messages over the 500-byte request buffer or that are not 18
 * comma-separated text fields are logged (M003) and dead-lettered; so are requests whose transaction fails.
 */
@Component
class AuthRequestListener {

    /** W01-GET-BUFFER is 500 bytes. */
    static final int MAX_REQUEST_BYTES = 500;

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
        AuthRequest rq;
        try {
            String text = StandardCharsets.US_ASCII.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(body)).toString();
            rq = AuthRequest.parse(text);
        } catch (CharacterCodingException e) {
            errors.log("M003", Level.CRITICAL, Subsystem.MQ, "PARSE", "NOT TEXT", "FAILED TO READ REQUEST MQ", "");
            throw new AmqpRejectAndDontRequeueException("request is not ASCII text");
        } catch (AuthRequest.Unparsable e) {
            errors.log("M003", Level.CRITICAL, Subsystem.MQ, "PARSE", e.fields() + " FIELDS", "FAILED TO READ REQUEST MQ", "");
            throw new AmqpRejectAndDontRequeueException(e.getMessage());
        }
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
            rabbit.send("", replyTo, new Message(reply.text().getBytes(StandardCharsets.US_ASCII), replyProperties(in)));
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
        out.setContentEncoding(StandardCharsets.US_ASCII.name());
        out.setMessageId(UUID.randomUUID().toString());
        out.setType("reply");
        out.setExpiration("5000");
        String correlation = request.getMessageId() != null ? request.getMessageId() : request.getCorrelationId();
        if (correlation != null) {
            out.setCorrelationId(correlation);
        }
        return out;
    }
}
