package org.loomsip.message;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * A lossless URI value used in a SIP request line. Detailed SIP URI component
 * parsing will be added independently without changing the message model.
 */
public record SipUri(String value) {

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

    public static SipUri parse(String value) {
        return new SipUri(value);
    }

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
