package org.loomsip.exchange;

/** Indicates that a logical request exhausted its configured attempt bound. */
public final class RequestAttemptLimitException extends IllegalStateException {

    /**
     * Creates an attempt-limit failure.
     *
     * @param maxAttempts configured total attempt limit
     */
    public RequestAttemptLimitException(int maxAttempts) {
        super("logical request attempt limit reached: " + maxAttempts);
    }
}
