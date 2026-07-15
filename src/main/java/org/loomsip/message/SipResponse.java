package org.loomsip.message;

import java.util.Objects;

/**
 * Immutable SIP response including its status-line, headers, and body.
 *
 * @param version SIP protocol version
 * @param statusCode three-digit response status in the range 100 through 699
 * @param reasonPhrase reason phrase without CR or LF characters; may be empty
 * @param headers immutable ordered response headers
 * @param body immutable binary response body
 */
public record SipResponse(
        SipVersion version,
        int statusCode,
        String reasonPhrase,
        SipHeaders headers,
        SipBody body
) implements SipMessage {

    /**
     * Validates and creates a complete response.
     *
     * @throws NullPointerException if an object component is {@code null}
     * @throws IllegalArgumentException if the status is outside 100-699 or the reason contains CR/LF
     */
    public SipResponse {
        Objects.requireNonNull(version, "version");
        if (statusCode < 100 || statusCode > 699) {
            throw new IllegalArgumentException("SIP status code must be between 100 and 699");
        }
        SipSyntax.requireSingleLine(reasonPhrase, "reason phrase");
        Objects.requireNonNull(headers, "headers");
        Objects.requireNonNull(body, "body");
    }

    /**
     * Creates an empty-body SIP/2.0 response.
     *
     * @param statusCode response status in the range 100 through 699
     * @param reasonPhrase response reason phrase; may be empty
     * @param headers immutable ordered headers
     * @throws NullPointerException if an object argument is {@code null}
     * @throws IllegalArgumentException if the response components are invalid
     */
    public SipResponse(int statusCode, String reasonPhrase, SipHeaders headers) {
        this(SipVersion.SIP_2_0, statusCode, reasonPhrase, headers, SipBody.empty());
    }
}
