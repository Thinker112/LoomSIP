package org.loomsip.message;

/**
 * Lossless generic representation of one SIP header line.
 *
 * <p>Header values remain untyped in the message core so unknown extension
 * headers can be parsed and encoded without data loss.</p>
 *
 * @param name header field name; must be a valid SIP token
 * @param value unfolded header value without CR or LF characters
 */
public record SipHeader(String name, String value) {

    /**
     * Validates and creates a header.
     *
     * @throws NullPointerException if {@code name} or {@code value} is {@code null}
     * @throws IllegalArgumentException if the name is not a token or the value contains CR/LF
     */
    public SipHeader {
        SipSyntax.requireToken(name, "header name");
        SipSyntax.requireSingleLine(value, "header value");
    }
}
