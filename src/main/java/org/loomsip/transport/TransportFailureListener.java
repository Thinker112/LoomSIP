package org.loomsip.transport;

/** Receives non-blocking notifications for failed transport sends. */
@FunctionalInterface
public interface TransportFailureListener {

    /**
     * Handles one send failure.
     *
     * <p>Implementations must enqueue work into the owning Transaction or
     * Dialog mailbox and must not mutate protocol state on a Netty callback
     * thread.</p>
     *
     * @param failure immutable failure event
     */
    void onTransportFailure(TransportFailureEvent failure);
}
