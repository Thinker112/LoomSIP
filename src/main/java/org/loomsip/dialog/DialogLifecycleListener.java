package org.loomsip.dialog;

import org.loomsip.message.SipMessage;
import org.loomsip.message.SipRequest;
import org.loomsip.transport.TransportContext;

/** Ordered TU notifications emitted by Dialog lifecycle infrastructure. */
public interface DialogLifecycleListener {

    /**
     * Reports a completed state transition.
     *
     * @param dialog Dialog handle
     * @param previous previous state
     * @param current current state
     */
    default void onStateChanged(DialogHandle dialog, DialogState previous, DialogState current) {
    }

    /**
     * Reports completed Dialog cleanup.
     *
     * @param dialog terminated Dialog
     * @param reason termination reason
     */
    default void onTerminated(DialogHandle dialog, DialogTerminationReason reason) {
    }

    /**
     * Reports infrastructure failure associated with one Dialog.
     *
     * @param dialog affected Dialog
     * @param cause failure
     */
    default void onFailure(DialogHandle dialog, Throwable cause) {
    }

    /**
     * Reports infrastructure failure associated with the manager itself.
     *
     * @param cause failure
     */
    default void onManagerFailure(Throwable cause) {
    }

    /**
     * Reports a transaction-independent ACK matched to a UAS 2xx exchange.
     *
     * @param dialog acknowledged Dialog
     * @param ack inbound ACK
     * @param context ACK network metadata
     */
    default void onAckReceived(DialogHandle dialog, SipRequest ack, TransportContext context) {
    }

    /**
     * Reports that no matching ACK arrived within the UAS 2xx timeout.
     *
     * @param dialog affected Dialog
     * @param inviteCSeq timed-out INVITE sequence number
     */
    default void onAckTimeout(DialogHandle dialog, long inviteCSeq) {
    }

    /**
     * Reports a failed Dialog-owned ACK or 2xx retransmission write.
     *
     * @param dialog affected Dialog
     * @param message message whose write failed
     * @param cause transport failure
     */
    default void onReliabilityTransportFailure(
            DialogHandle dialog,
            SipMessage message,
            Throwable cause
    ) {
    }
}
