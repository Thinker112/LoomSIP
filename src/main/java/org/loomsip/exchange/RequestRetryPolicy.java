package org.loomsip.exchange;

/**
 * Hard bound on transaction attempts created for one logical SIP request.
 *
 * @param maxAttempts total attempts including the initial request
 */
public record RequestRetryPolicy(int maxAttempts) {

    /** Conservative challenge plus one retry policy. */
    public static final RequestRetryPolicy DEFAULT = new RequestRetryPolicy(2);

    /** Maximum configurable attempts accepted by the core. */
    public static final int MAX_SUPPORTED_ATTEMPTS = 16;

    /** Validates the positive, bounded attempt limit. */
    public RequestRetryPolicy {
        if (maxAttempts <= 0 || maxAttempts > MAX_SUPPORTED_ATTEMPTS) {
            throw new IllegalArgumentException(
                    "maxAttempts must be between 1 and " + MAX_SUPPORTED_ATTEMPTS
            );
        }
    }
}
