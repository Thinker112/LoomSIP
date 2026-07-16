package org.loomsip.transaction.invite;

import org.loomsip.message.SipRequest;
import org.loomsip.transaction.timer.SipTimer;
import org.loomsip.transport.TransportContext;

/** Ordered TU notifications emitted by INVITE server transactions and routing. */
public interface InviteServerListener {

    /**
     * Delivers the initial INVITE exactly once for a server transaction.
     *
     * @param transaction response-capable IST
     * @param request initial INVITE
     * @param context request network metadata
     */
    void onInvite(InviteServerHandle transaction, SipRequest request, TransportContext context);

    /**
     * Delivers the first ACK matched to a non-2xx final response.
     *
     * <p>Retransmitted ACK requests absorbed in Confirmed are not delivered again.</p>
     *
     * @param transaction acknowledged IST
     * @param ack matched ACK
     * @param context ACK network metadata
     */
    default void onAck(InviteServerHandle transaction, SipRequest ack, TransportContext context) {
    }

    /**
     * Delivers an ACK that did not match a server transaction, normally a 2xx ACK.
     *
     * <p>Dialog routing is introduced after the transaction milestones, so 3C
     * exposes this boundary without interpreting the ACK.</p>
     *
     * @param ack unmatched ACK
     * @param context ACK network metadata
     */
    default void onUnmatchedAck(SipRequest ack, TransportContext context) {
    }

    /**
     * Reports Timer H expiry while waiting for a non-2xx ACK.
     *
     * @param transaction timed-out IST
     * @param timer timer that ended the transaction
     */
    default void onTimeout(InviteServerHandle transaction, SipTimer timer) {
    }

    /**
     * Reports a local response transport failure.
     *
     * @param transaction affected IST
     * @param cause transport failure
     */
    default void onTransportFailure(InviteServerHandle transaction, Throwable cause) {
    }

    /**
     * Reports completed transaction cleanup.
     *
     * @param transaction terminated IST
     */
    default void onTerminated(InviteServerHandle transaction) {
    }

    /**
     * Reports routing, callback, or lifecycle infrastructure failures.
     *
     * @param cause layer failure
     */
    default void onLayerError(Throwable cause) {
    }
}
