package org.loomsip.exchange;

import org.loomsip.message.SipRequest;

import java.util.Objects;
import java.util.Optional;

/**
 * Immutable input supplied when one transaction attempt is started.
 *
 * @param attemptNumber one-based attempt number
 * @param request immutable request for this attempt
 * @param previousRequest previous attempt request, empty for the initial attempt
 */
public record RequestAttemptContext(
        int attemptNumber,
        SipRequest request,
        Optional<SipRequest> previousRequest
) {

    /** Validates numbering and immutable request references. */
    public RequestAttemptContext {
        if (attemptNumber <= 0) {
            throw new IllegalArgumentException("attemptNumber must be positive");
        }
        Objects.requireNonNull(request, "request");
        previousRequest = Objects.requireNonNull(previousRequest, "previousRequest");
        if (attemptNumber == 1 && previousRequest.isPresent()) {
            throw new IllegalArgumentException("initial attempt cannot have a previous request");
        }
        if (attemptNumber > 1 && previousRequest.isEmpty()) {
            throw new IllegalArgumentException("retry attempt requires a previous request");
        }
    }

    /**
     * Returns whether this context represents a retry.
     *
     * @return {@code true} for attempt two and later
     */
    public boolean retry() {
        return attemptNumber > 1;
    }
}
