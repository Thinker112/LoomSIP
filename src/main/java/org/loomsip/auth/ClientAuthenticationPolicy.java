package org.loomsip.auth;

import java.util.Objects;
import java.util.Set;

/**
 * Local UAC policy for supported algorithms and challenge retry bounds.
 *
 * @param allowedAlgorithms accepted Digest algorithms
 * @param maxChallengeRetries maximum authentication retries after the initial attempt
 */
public record ClientAuthenticationPolicy(
        Set<DigestAlgorithm> allowedAlgorithms,
        int maxChallengeRetries
) {

    /** Supports SHA-256 when offered and retains MD5 interoperability. */
    public static final ClientAuthenticationPolicy DEFAULT = new ClientAuthenticationPolicy(
            Set.of(DigestAlgorithm.SHA_256, DigestAlgorithm.MD5),
            1
    );

    /** Validates a non-empty allow-list and bounded retry limit. */
    public ClientAuthenticationPolicy {
        allowedAlgorithms = Set.copyOf(Objects.requireNonNull(allowedAlgorithms, "allowedAlgorithms"));
        if (allowedAlgorithms.isEmpty()) {
            throw new IllegalArgumentException("allowedAlgorithms must not be empty");
        }
        if (maxChallengeRetries <= 0 || maxChallengeRetries > 16) {
            throw new IllegalArgumentException("maxChallengeRetries must be between 1 and 16");
        }
    }

    /**
     * Tests whether a parsed challenge is allowed by this client.
     *
     * @param challenge parsed challenge
     * @return {@code true} when algorithm and qop are both supported
     */
    public boolean supports(DigestChallenge challenge) {
        Objects.requireNonNull(challenge, "challenge");
        return allowedAlgorithms.contains(challenge.algorithm()) && challenge.supportsAuthQop();
    }
}
