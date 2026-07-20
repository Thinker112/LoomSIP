package org.loomsip.dialog;

import java.util.concurrent.CompletableFuture;

/** Completion of one serialized Session Timer configuration request. */
record DialogSessionTimerConfigured(
        DialogSessionState state,
        Throwable failure,
        CompletableFuture<DialogSessionState> result
) implements DialogEvent {

    DialogSessionTimerConfigured {
        java.util.Objects.requireNonNull(result, "result");
        if ((state == null) == (failure == null)) {
            throw new IllegalArgumentException("exactly one of state or failure is required");
        }
    }
}
