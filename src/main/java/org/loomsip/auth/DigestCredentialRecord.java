package org.loomsip.auth;

import java.util.Locale;
import java.util.Objects;

/**
 * Server-side precomputed HA1 credential record.
 *
 * <p>The server never needs the user's plaintext password. HA1 remains secret
 * material and is deliberately omitted from {@link #toString()}.</p>
 *
 * @param username Digest username
 * @param realm Digest protection space
 * @param algorithm algorithm used to compute HA1
 * @param ha1 lower-case hexadecimal {@code H(username:realm:password)}
 */
public record DigestCredentialRecord(
        String username,
        String realm,
        DigestAlgorithm algorithm,
        String ha1
) {

    /** Validates record identity and the algorithm-specific HA1 length. */
    public DigestCredentialRecord {
        username = requireText(username, "username");
        realm = requireText(realm, "realm");
        algorithm = Objects.requireNonNull(algorithm, "algorithm");
        ha1 = requireHex(ha1, "ha1");
        int expectedLength = algorithm == DigestAlgorithm.MD5 ? 32 : 64;
        if (ha1.length() != expectedLength) {
            throw new IllegalArgumentException("ha1 length does not match Digest algorithm");
        }
    }

    /**
     * Returns a diagnostic form without the HA1 value.
     *
     * @return safe credential record diagnostic
     */
    @Override
    public String toString() {
        return "DigestCredentialRecord[username=" + username + ", realm=" + realm
                + ", algorithm=" + algorithm.wireName() + "]";
    }

    private static String requireText(String value, String name) {
        value = Objects.requireNonNull(value, name);
        if (value.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be empty");
        }
        return value;
    }

    private static String requireHex(String value, String name) {
        value = requireText(value, name).toLowerCase(Locale.ROOT);
        if (!value.chars().allMatch(character ->
                character >= '0' && character <= '9' || character >= 'a' && character <= 'f')) {
            throw new IllegalArgumentException(name + " must be lowercase hexadecimal");
        }
        return value;
    }
}
