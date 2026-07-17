package org.loomsip.dialog;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/** Command requesting a validated Dialog state transition. */
record DialogStateTransition(
        DialogState target,
        DialogTerminationReason terminationReason,
        CompletableFuture<Void> result
) implements DialogEvent {

    DialogStateTransition {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(terminationReason, "terminationReason");
        Objects.requireNonNull(result, "result");
    }
}
