package org.loomsip.dialog;

import java.util.concurrent.CompletableFuture;

/** One bounded 422 Session Interval Too Small retry command. */
record DialogSessionRefreshRetryRequested(
        long sequenceNumber,
        int minimumSeconds,
        CompletableFuture<Boolean> result
) implements DialogEvent {

    DialogSessionRefreshRetryRequested {
        if (sequenceNumber <= 0) {
            throw new IllegalArgumentException("sequenceNumber must be positive");
        }
        if (minimumSeconds <= 0) {
            throw new IllegalArgumentException("minimumSeconds must be positive");
        }
        java.util.Objects.requireNonNull(result, "result");
    }
}
