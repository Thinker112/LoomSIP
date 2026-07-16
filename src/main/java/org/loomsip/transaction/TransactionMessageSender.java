package org.loomsip.transaction;

import org.loomsip.message.SipMessage;
import org.loomsip.transport.SendResult;
import org.loomsip.transport.TransportEndpoint;

import java.util.concurrent.CompletionStage;

/**
 * Transport write boundary used by transaction state machines.
 *
 * <pre>{@code
 * Transaction State Machine
 *           |
 *           v
 * TransactionMessageSender
 *       |             |
 *       v             v
 * SipTransport    Test Network
 *       |             |
 *       +------+------+
 *              v
 * CompletionStage<SendResult>
 * }</pre>
 */
@FunctionalInterface
public interface TransactionMessageSender {

    /**
     * Sends one immutable message without applying transaction behavior.
     *
     * @param message request or response
     * @param target selected remote endpoint
     * @return local transport write completion
     */
    CompletionStage<SendResult> send(SipMessage message, TransportEndpoint target);
}
