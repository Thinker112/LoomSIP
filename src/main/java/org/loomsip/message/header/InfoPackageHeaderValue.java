package org.loomsip.message.header;

import java.util.Locale;
import java.util.Objects;

/**
 * Typed RFC 6086 INFO package token.
 *
 * <p>The token is preserved for wire rendering and compared case-insensitively
 * through {@link #normalizedName()}.</p>
 *
 * @param name INFO package token
 */
public record InfoPackageHeaderValue(String name) {

    /** Validates one SIP token package name. */
    public InfoPackageHeaderValue {
        name = HeaderSyntax.requireToken(Objects.requireNonNull(name, "name"), "INFO package name");
    }

    /**
     * Returns a case-insensitive comparison key for this package.
     *
     * @return lower-case package token
     */
    public String normalizedName() {
        return name.toLowerCase(Locale.ROOT);
    }

    /**
     * Renders this value for an Info-Package header.
     *
     * @return package token
     */
    public String wireValue() {
        return name;
    }
}
