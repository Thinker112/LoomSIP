package org.loomsip.auth;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Immutable parsed Digest challenge without credential material.
 *
 * @param realm challenge protection space
 * @param nonce server nonce
 * @param opaque optional opaque server data
 * @param algorithm supported digest algorithm
 * @param qopOptions normalized offered qop tokens
 * @param stale whether the server reports an expired nonce
 * @param charset digest input charset
 */
public record DigestChallenge(
        String realm,
        String nonce,
        Optional<String> opaque,
        DigestAlgorithm algorithm,
        Set<String> qopOptions,
        boolean stale,
        DigestCharset charset
) {

    /** Validates challenge fields and normalizes offered qop tokens. */
    public DigestChallenge {
        realm = requireText(realm, "realm");
        nonce = requireText(nonce, "nonce");
        opaque = Objects.requireNonNull(opaque, "opaque");
        algorithm = Objects.requireNonNull(algorithm, "algorithm");
        qopOptions = Set.copyOf(Objects.requireNonNull(qopOptions, "qopOptions").stream()
                .map(option -> requireText(option, "qop option").toLowerCase(Locale.ROOT))
                .toList());
        charset = Objects.requireNonNull(charset, "charset");
    }

    /**
     * Returns whether this challenge explicitly offers {@code qop=auth}.
     *
     * @return {@code true} when the initial implementation can calculate qop auth
     */
    public boolean supportsAuthQop() {
        return qopOptions.contains(DigestQop.AUTH.wireName());
    }

    private static String requireText(String value, String name) {
        value = Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
