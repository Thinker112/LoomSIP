package org.loomsip.auth;

import java.time.Instant;
import java.util.Objects;

/** Immutable server-issued nonce bound to one Digest policy configuration. */
public record DigestNonce(
        String value,
        String realm,
        DigestAlgorithm algorithm,
        DigestCharset charset,
        Instant expiresAt
) {

    /** Validates nonce ownership and expiry metadata. */
    public DigestNonce {
        value = requireText(value, "value");
        realm = requireText(realm, "realm");
        algorithm = Objects.requireNonNull(algorithm, "algorithm");
        charset = Objects.requireNonNull(charset, "charset");
        expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
    }

    /**
     * Returns a diagnostic form without the nonce token.
     *
     * @return safe nonce diagnostic
     */
    @Override
    public String toString() {
        return "DigestNonce[realm=" + realm + ", algorithm=" + algorithm.wireName()
                + ", expiresAt=" + expiresAt + "]";
    }

    private static String requireText(String value, String name) {
        value = Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
