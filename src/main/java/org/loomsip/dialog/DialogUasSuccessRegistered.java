package org.loomsip.dialog;

import org.loomsip.message.SipResponse;
import org.loomsip.transaction.TransportReliability;
import org.loomsip.transport.TransportEndpoint;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/** Command registering UAS 2xx retransmission and ACK-wait state before send. */
record DialogUasSuccessRegistered(
        SipResponse response,
        TransportEndpoint responseTarget,
        TransportReliability reliability,
        CompletableFuture<Void> result
) implements DialogEvent {

    DialogUasSuccessRegistered {
        Objects.requireNonNull(response, "response");
        Objects.requireNonNull(responseTarget, "responseTarget");
        Objects.requireNonNull(reliability, "reliability");
        Objects.requireNonNull(result, "result");
    }
}
