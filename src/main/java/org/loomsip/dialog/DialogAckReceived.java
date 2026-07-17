package org.loomsip.dialog;

import org.loomsip.message.SipRequest;
import org.loomsip.transport.TransportContext;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/** Command matching one transaction-independent ACK to a UAS 2xx exchange. */
record DialogAckReceived(
        SipRequest ack,
        TransportContext context,
        CompletableFuture<Boolean> result
) implements DialogEvent {

    DialogAckReceived {
        Objects.requireNonNull(ack, "ack");
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(result, "result");
    }
}
