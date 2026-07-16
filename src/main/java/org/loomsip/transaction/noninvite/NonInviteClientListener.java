package org.loomsip.transaction.noninvite;

import org.loomsip.message.SipResponse;
import org.loomsip.transaction.timer.SipTimer;
import org.loomsip.transport.TransportContext;

/**
 * Ordered TU notifications emitted by Non-INVITE client transactions.
 */
public interface NonInviteClientListener {

    /**
     * Receives a provisional or final response after the state transition completes.
     *
     * @param transaction client transaction handle
     * @param response inbound response
     * @param context response network metadata
     */
    void onResponse(
            ClientTransactionHandle transaction,
            SipResponse response,
            TransportContext context
    );

    /**
     * Reports a transaction timer timeout.
     *
     * @param transaction client transaction handle
     * @param timer timer that ended the transaction
     */
    default void onTimeout(ClientTransactionHandle transaction, SipTimer timer) {
    }

    /**
     * Reports a local transport write failure.
     *
     * @param transaction client transaction handle
     * @param cause transport failure
     */
    default void onTransportFailure(ClientTransactionHandle transaction, Throwable cause) {
    }

    /**
     * Reports completed transaction cleanup.
     *
     * @param transaction terminated handle
     */
    default void onTerminated(ClientTransactionHandle transaction) {
    }

    /**
     * Reports Dispatcher, routing, or callback infrastructure failures.
     *
     * @param cause layer failure
     */
    default void onLayerError(Throwable cause) {
    }
}
