package org.loomsip.transaction.noninvite;

import org.loomsip.message.SipRequest;
import org.loomsip.transport.TransportContext;

/**
 * Ordered TU notifications emitted by Non-INVITE server transactions.
 */
public interface NonInviteServerListener {

    /**
     * Delivers the initial request exactly once for a server transaction.
     *
     * @param transaction response-capable server handle
     * @param request initial request
     * @param context request network metadata
     */
    void onRequest(
            ServerTransactionHandle transaction,
            SipRequest request,
            TransportContext context
    );

    /**
     * Reports a local response transport failure.
     *
     * @param transaction server transaction handle
     * @param cause transport failure
     */
    default void onTransportFailure(ServerTransactionHandle transaction, Throwable cause) {
    }

    /**
     * Reports completed transaction cleanup.
     *
     * @param transaction terminated handle
     */
    default void onTerminated(ServerTransactionHandle transaction) {
    }

    /**
     * Reports Dispatcher, routing, or callback infrastructure failures.
     *
     * @param cause layer failure
     */
    default void onLayerError(Throwable cause) {
    }
}
