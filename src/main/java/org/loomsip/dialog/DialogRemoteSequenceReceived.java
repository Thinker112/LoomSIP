package org.loomsip.dialog;

import org.loomsip.message.header.CSeqHeaderValue;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/** Command validating and accepting a strictly increasing remote CSeq. */
record DialogRemoteSequenceReceived(
        long sequenceNumber,
        CompletableFuture<Void> result
) implements DialogEvent {

    DialogRemoteSequenceReceived {
        Objects.requireNonNull(result, "result");
        if (sequenceNumber < 0 || sequenceNumber > CSeqHeaderValue.MAX_SEQUENCE_NUMBER) {
            throw new IllegalArgumentException("remote CSeq must be a valid SIP sequence number");
        }
    }
}
