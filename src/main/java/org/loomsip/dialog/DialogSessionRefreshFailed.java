package org.loomsip.dialog;

/** Transaction-level failure for one generation-bound automatic refresh. */
record DialogSessionRefreshFailed(
        long sequenceNumber,
        Throwable cause
) implements DialogEvent {

    DialogSessionRefreshFailed {
        if (sequenceNumber <= 0) {
            throw new IllegalArgumentException("sequenceNumber must be positive");
        }
        java.util.Objects.requireNonNull(cause, "cause");
    }
}
