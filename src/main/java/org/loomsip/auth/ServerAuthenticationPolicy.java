package org.loomsip.auth;

import java.time.Duration;
import java.util.Comparator;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Security and capacity policy for one UAS Digest protection space.
 *
 * @param realm Digest protection space
 * @param allowedAlgorithms supported challenge and credential algorithms
 * @param charset digest input charset for this protection space
 * @param nonceLifetime validity duration for newly issued nonces
 * @param maxActiveNonces bounded active nonce count
 * @param maxReplayUsersPerNonce maximum username/cnonce replay entries per nonce
 * @param opaque optional opaque value echoed by compliant clients
 */
public record ServerAuthenticationPolicy(
        String realm,
        Set<DigestAlgorithm> allowedAlgorithms,
        DigestCharset charset,
        Duration nonceLifetime,
        int maxActiveNonces,
        int maxReplayUsersPerNonce,
        Optional<String> opaque
) {

    /** Validates the realm, supported algorithms, timing, and resource limits. */
    public ServerAuthenticationPolicy {
        realm = requireText(realm, "realm");
        allowedAlgorithms = Set.copyOf(Objects.requireNonNull(allowedAlgorithms, "allowedAlgorithms"));
        if (allowedAlgorithms.isEmpty()) {
            throw new IllegalArgumentException("allowedAlgorithms must not be empty");
        }
        charset = Objects.requireNonNull(charset, "charset");
        nonceLifetime = Objects.requireNonNull(nonceLifetime, "nonceLifetime");
        if (nonceLifetime.isNegative() || nonceLifetime.isZero() || nonceLifetime.compareTo(Duration.ofHours(24)) > 0) {
            throw new IllegalArgumentException("nonceLifetime must be positive and no longer than 24 hours");
        }
        if (maxActiveNonces <= 0 || maxActiveNonces > 1_000_000) {
            throw new IllegalArgumentException("maxActiveNonces must be between 1 and 1000000");
        }
        if (maxReplayUsersPerNonce <= 0 || maxReplayUsersPerNonce > 100_000) {
            throw new IllegalArgumentException("maxReplayUsersPerNonce must be between 1 and 100000");
        }
        opaque = Objects.requireNonNull(opaque, "opaque").map(value -> requireText(value, "opaque"));
    }

    /**
     * Creates the secure default policy for a realm.
     *
     * @param realm Digest protection space
     * @return SHA-256-preferred policy retaining MD5 interoperability
     */
    public static ServerAuthenticationPolicy standard(String realm) {
        return new ServerAuthenticationPolicy(
                realm,
                Set.of(DigestAlgorithm.SHA_256, DigestAlgorithm.MD5),
                DigestCharset.ISO_8859_1,
                Duration.ofMinutes(5),
                4096,
                64,
                Optional.empty()
        );
    }

    /**
     * Selects the strongest configured challenge algorithm.
     *
     * @return strongest allowed algorithm
     */
    public DigestAlgorithm preferredAlgorithm() {
        return allowedAlgorithms.stream().max(Comparator.comparingInt(DigestAlgorithm::strength)).orElseThrow();
    }

    private static String requireText(String value, String name) {
        value = Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
