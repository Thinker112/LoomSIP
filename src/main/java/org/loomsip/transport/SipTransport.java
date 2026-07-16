package org.loomsip.transport;

import org.loomsip.message.SipMessage;

import java.util.concurrent.CompletionStage;

/**
 * One-shot network transport for complete immutable SIP messages.
 *
 * <p>A transport must be started before sending. Closing is idempotent, but a
 * closed or failed instance cannot be restarted. This interface deliberately
 * does not correlate responses or implement transaction timers.</p>
 */
public interface SipTransport extends AutoCloseable {

    /**
     * Allocates transport resources and synchronously binds the local endpoint.
     *
     * @throws TransportException if the current state disallows startup or binding fails
     */
    void start() throws TransportException;

    /**
     * Submits one encoded SIP message to a remote endpoint.
     *
     * @param message message to encode and send
     * @param target remote target using a protocol supported by this transport
     * @return asynchronous write result; lifecycle and network failures complete it exceptionally
     * @throws NullPointerException if an argument is {@code null}
     */
    CompletionStage<SendResult> send(SipMessage message, TransportEndpoint target);

    /**
     * Returns the actual bound endpoint, including an operating-system-assigned port.
     *
     * @return local endpoint from the successful start
     * @throws IllegalStateException if binding has never completed successfully
     */
    TransportEndpoint localEndpoint();

    /**
     * Returns the current lifecycle state.
     *
     * @return current state
     */
    TransportState state();

    /**
     * Stops inbound work and releases all resources owned by this transport.
     * Repeated invocations are safe.
     */
    @Override
    void close();
}
