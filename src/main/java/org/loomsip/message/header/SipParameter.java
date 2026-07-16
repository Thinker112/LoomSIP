package org.loomsip.message.header;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * One case-insensitive SIP header parameter with an optional decoded value.
 *
 * @param name lowercase parameter name
 * @param value decoded value, or empty for a flag parameter such as {@code rport}
 */
public record SipParameter(String name, Optional<String> value) {

    /**
     * Validates and creates a parameter.
     *
     * @throws NullPointerException if a component is {@code null}
     * @throws IllegalArgumentException if the name is not a token
     */
    public SipParameter {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(value, "value");
        name = name.toLowerCase(Locale.ROOT);
        HeaderSyntax.requireToken(name, "parameter name");
    }
}
