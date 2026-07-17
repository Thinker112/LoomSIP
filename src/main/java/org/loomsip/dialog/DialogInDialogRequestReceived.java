package org.loomsip.dialog;

import org.loomsip.message.SipRequest;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/** Command that validates one new remote request against Dialog state. */
record DialogInDialogRequestReceived(
        SipRequest request,
        CompletableFuture<Void> result
) implements DialogEvent {

    DialogInDialogRequestReceived {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(result, "result");
    }
}
