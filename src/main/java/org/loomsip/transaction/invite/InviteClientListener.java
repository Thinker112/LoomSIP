package org.loomsip.transaction.invite;

import org.loomsip.message.SipResponse;
import org.loomsip.transaction.timer.SipTimer;
import org.loomsip.transport.TransportContext;

/** Ordered TU notifications emitted by INVITE client transactions. */
public interface InviteClientListener {

    /**
     * Receives a provisional or final response after its state transition.
     *
     * @param transaction client transaction
     * @param response inbound response
     * @param context response network metadata
     */
    void onResponse(InviteClientHandle transaction, SipResponse response, TransportContext context);

    /**
     * Reports Timer B expiry.
     *
     * @param transaction timed-out ICT
     * @param timer timer that ended the transaction
     */
    default void onTimeout(InviteClientHandle transaction, SipTimer timer) {
    }

    /**
     * Reports a local INVITE or ACK transport failure.
     *
     * @param transaction affected ICT
     * @param cause transport failure
     */
    default void onTransportFailure(InviteClientHandle transaction, Throwable cause) {
    }

    /**
     * Reports completed transaction cleanup.
     *
     * @param transaction terminated ICT
     */
    default void onTerminated(InviteClientHandle transaction) {
    }

    /**
     * Reports routing, callback, or lifecycle infrastructure failures.
     *
     * @param cause layer failure
     */
    default void onLayerError(Throwable cause) {
    }
}
