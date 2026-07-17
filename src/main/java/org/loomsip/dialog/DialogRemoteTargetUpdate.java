package org.loomsip.dialog;

import org.loomsip.message.SipUri;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/** Command replacing the current Dialog Remote Target. */
record DialogRemoteTargetUpdate(
        SipUri remoteTarget,
        CompletableFuture<Void> result
) implements DialogEvent {

    DialogRemoteTargetUpdate {
        Objects.requireNonNull(remoteTarget, "remoteTarget");
        Objects.requireNonNull(result, "result");
    }
}
