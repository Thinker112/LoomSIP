package org.loomsip.exchange;

import org.loomsip.message.SipRequest;

import java.util.Objects;

/**
 * Started transaction handle associated with one immutable request attempt.
 *
 * @param attemptNumber one-based attempt number
 * @param request immutable request sent by this attempt
 * @param handle concrete INVITE or Non-INVITE transaction handle
 * @param <T> transaction handle type
 */
public record RequestAttempt<T>(int attemptNumber, SipRequest request, T handle) {

    /** Validates numbering, request, and handle. */
    public RequestAttempt {
        if (attemptNumber <= 0) {
            throw new IllegalArgumentException("attemptNumber must be positive");
        }
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(handle, "handle");
    }
}
