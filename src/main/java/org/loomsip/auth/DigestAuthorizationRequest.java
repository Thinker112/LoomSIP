package org.loomsip.auth;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * Parsed client Digest authorization parameters without credential material.
 *
 * <p>The record intentionally does not render its response hash in
 * {@link #toString()}, because it is authentication material.</p>
 *
 * @param username claimed Digest username
 * @param realm challenged protection space
 * @param nonce server nonce returned by the client
 * @param uri request URI used in HA2
 * @param response client response hash
 * @param algorithm selected digest algorithm
 * @param qop selected quality-of-protection value
 * @param nonceCount eight-digit qop nonce count
 * @param cnonce client nonce
 * @param opaque optional echoed server data
 */
public record DigestAuthorizationRequest(
        String username,
        String realm,
        String nonce,
        String uri,
        String response,
        DigestAlgorithm algorithm,
        DigestQop qop,
        String nonceCount,
        String cnonce,
        Optional<String> opaque
) {

    /** Validates supported qop-auth authorization parameters. */
    public DigestAuthorizationRequest {
        username = requireText(username, "username");
        realm = requireText(realm, "realm");
        nonce = requireText(nonce, "nonce");
        uri = requireText(uri, "uri");
        response = requireHex(response, "response");
        algorithm = Objects.requireNonNull(algorithm, "algorithm");
        qop = Objects.requireNonNull(qop, "qop");
        nonceCount = requireNonceCount(nonceCount);
        cnonce = requireText(cnonce, "cnonce");
        opaque = Objects.requireNonNull(opaque, "opaque");
    }

    /**
     * Returns a diagnostic form without nonce, cnonce, or response fields.
     *
     * @return safe authorization diagnostic
     */
    @Override
    public String toString() {
        return "DigestAuthorizationRequest[username=" + username + ", realm=" + realm
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

    private static String requireNonceCount(String value) {
        value = requireHex(value, "nonceCount");
        if (value.length() != 8 || Long.parseLong(value, 16) == 0) {
            throw new IllegalArgumentException("nonceCount must be a non-zero eight-digit hexadecimal value");
        }
        return value;
    }
}
