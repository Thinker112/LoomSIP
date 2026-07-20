package org.loomsip.auth;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable Authorization parameters calculated for one supported challenge.
 *
 * @param username authenticated username
 * @param realm challenge realm
 * @param nonce server nonce
 * @param uri digest request URI value
 * @param response calculated hexadecimal response hash
 * @param algorithm selected digest algorithm
 * @param qop selected qop value
 * @param nonceCount eight-digit hexadecimal nonce count
 * @param cnonce client nonce
 * @param opaque optional echoed server opaque value
 */
public record DigestAuthorization(
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

    /** Validates immutable authorization fields. */
    public DigestAuthorization {
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
     * Renders this value after the {@code Authorization:} header name.
     *
     * @return RFC-compatible Digest authorization value
     */
    public String wireValue() {
        StringBuilder value = new StringBuilder("Digest ")
                .append("username=").append(quote(username))
                .append(", realm=").append(quote(realm))
                .append(", nonce=").append(quote(nonce))
                .append(", uri=").append(quote(uri))
                .append(", response=").append(quote(response))
                .append(", algorithm=").append(algorithm.wireName())
                .append(", qop=").append(qop.wireName())
                .append(", nc=").append(nonceCount)
                .append(", cnonce=").append(quote(cnonce));
        opaque.ifPresent(opaqueValue -> value.append(", opaque=").append(quote(opaqueValue)));
        return value.toString();
    }

    /**
     * Returns a diagnostic form that deliberately omits nonce, response, and cnonce values.
     *
     * @return safe authorization diagnostic
     */
    @Override
    public String toString() {
        return "DigestAuthorization[username=" + username + ", realm=" + realm
                + ", algorithm=" + algorithm.wireName() + "]";
    }

    private static String quote(String value) {
        return '"' + value.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
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
        if (value.length() != 8) {
            throw new IllegalArgumentException("nonceCount must contain exactly eight hexadecimal digits");
        }
        return value;
    }
}
