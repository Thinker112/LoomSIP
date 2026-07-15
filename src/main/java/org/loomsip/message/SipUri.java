package org.loomsip.message;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * Lossless URI value used in a SIP request-line.
 *
 * <p>The value retains extension schemes and URI parameters verbatim. Detailed
 * SIP URI component parsing can be added without changing the message model.
 * The special {@code *} request target is also supported.</p>
 *
 * @param value complete request URI or {@code *}
 */
public record SipUri(String value) {

    /**
     * Validates and creates a URI value.
     *
     * @throws NullPointerException if {@code value} is {@code null}
     * @throws IllegalArgumentException if the URI is empty, contains whitespace,
     *                                  has control characters, or has no scheme
     */
    public SipUri {
        Objects.requireNonNull(value, "value");
        if (value.isEmpty()) {
            throw new IllegalArgumentException("URI must not be empty");
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isWhitespace(c) || Character.isISOControl(c)) {
                throw new IllegalArgumentException("URI contains whitespace or a control character at index " + i);
            }
        }
        if (!"*".equals(value)) {
            int colon = value.indexOf(':');
            if (colon <= 0) {
                throw new IllegalArgumentException("URI has no scheme: " + value);
            }
            SipSyntax.requireToken(value.substring(0, colon), "URI scheme");
        }
    }

    /**
     * Parses the lossless request URI representation.
     *
     * @param value complete URI text or {@code *}
     * @return validated URI value
     * @throws NullPointerException if {@code value} is {@code null}
     * @throws IllegalArgumentException if the URI is invalid
     */
    public static SipUri parse(String value) {
        return new SipUri(value);
    }

    /**
     * Returns the lowercase URI scheme.
     *
     * @return the scheme, or an empty optional for the {@code *} target
     */
    public Optional<String> scheme() {
        if ("*".equals(value)) {
            return Optional.empty();
        }
        return Optional.of(value.substring(0, value.indexOf(':')).toLowerCase(Locale.ROOT));
    }

    @Override
    public String toString() {
        return value;
    }
}
