package org.loomsip.transaction;

import org.loomsip.message.SipMessage;
import org.loomsip.transport.SendResult;
import org.loomsip.transport.TransportEndpoint;
import org.loomsip.transport.TransportFailureEvent;
import org.loomsip.transport.TransportFailureListener;
import org.loomsip.transport.TransportSelector;

import java.util.Objects;
import java.util.concurrent.CompletionStage;

/**
 * Adapts transport selection to the Transaction sender boundary.
 *
 * <pre>{@code
 * Transaction state machine
 *          |
 *          v
 * ConnectionAwareMessageSender
 *          |
 *          +--> TransportSelector --> UDP/TCP/TLS
 *          |
 *          +--> failure listener --> mailbox event adapter
 * }</pre>
 *
 * <p>The returned completion stage remains the authoritative per-write result;
 * the optional listener receives a diagnostic event and must enqueue any
 * protocol work into its owning mailbox.</p>
 */
public final class ConnectionAwareMessageSender implements TransactionMessageSender {

    private final TransportSelector selector;
    private final TransportFailureListener failureListener;

    /**
     * Creates an adapter without a separate failure listener.
     *
     * @param selector protocol transport selector
     */
    public ConnectionAwareMessageSender(TransportSelector selector) {
        this(selector, failure -> {
        });
    }

    /**
     * Creates an adapter with a non-blocking transport-failure listener.
     *
     * @param selector protocol transport selector
     * @param failureListener listener invoked for failed sends
     */
    public ConnectionAwareMessageSender(
            TransportSelector selector,
            TransportFailureListener failureListener
    ) {
        this.selector = Objects.requireNonNull(selector, "selector");
        this.failureListener = Objects.requireNonNull(failureListener, "failureListener");
    }

    @Override
    public CompletionStage<SendResult> send(SipMessage message, TransportEndpoint target) {
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(target, "target");
        CompletionStage<SendResult> stage;
        try {
            stage = Objects.requireNonNull(selector.send(message, target), "selector result");
        } catch (Throwable cause) {
            notifyFailure(target, cause);
            return java.util.concurrent.CompletableFuture.failedFuture(cause);
        }
        stage.whenComplete((result, failure) -> {
            if (failure != null) {
                notifyFailure(target, unwrap(failure));
            }
        });
        return stage;
    }

    private void notifyFailure(TransportEndpoint target, Throwable cause) {
        try {
            failureListener.onTransportFailure(new TransportFailureEvent(target, cause));
        } catch (Throwable ignored) {
            // Diagnostics must not replace the original send failure.
        }
    }

    private static Throwable unwrap(Throwable failure) {
        if ((failure instanceof java.util.concurrent.CompletionException
                || failure instanceof java.util.concurrent.ExecutionException)
                && failure.getCause() != null) {
            return failure.getCause();
        }
        return failure;
    }
}
