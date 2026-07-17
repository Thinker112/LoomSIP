package org.loomsip.dialog;

import org.loomsip.message.SipBody;
import org.loomsip.message.SipHeaders;
import org.loomsip.message.SipMethod;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/** Command that atomically allocates CSeq and constructs an in-Dialog request. */
record DialogRequestRequested(
        SipMethod method,
        SipHeaders additionalHeaders,
        SipBody body,
        CompletableFuture<DialogPreparedRequest> result
) implements DialogEvent {

    DialogRequestRequested {
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(additionalHeaders, "additionalHeaders");
        Objects.requireNonNull(body, "body");
        Objects.requireNonNull(result, "result");
    }
}
