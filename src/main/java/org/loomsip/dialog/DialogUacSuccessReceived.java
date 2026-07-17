package org.loomsip.dialog;

import org.loomsip.message.SipRequest;
import org.loomsip.message.SipResponse;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/** Command creating or obtaining the cached ACK for one UAC INVITE 2xx. */
record DialogUacSuccessReceived(
        SipRequest invite,
        SipResponse response,
        CompletableFuture<DialogAckTransmission> result
) implements DialogEvent {

    DialogUacSuccessReceived {
        Objects.requireNonNull(invite, "invite");
        Objects.requireNonNull(response, "response");
        Objects.requireNonNull(result, "result");
    }
}
