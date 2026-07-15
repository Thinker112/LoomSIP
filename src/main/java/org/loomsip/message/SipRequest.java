package org.loomsip.message;

import java.util.Objects;

/**
 * Immutable SIP request including its request-line, headers, and body.
 *
 * @param method request method, including extension methods
 * @param requestUri target URI from the request-line
 * @param version SIP protocol version
 * @param headers immutable ordered request headers
 * @param body immutable binary request body
 */
public record SipRequest(
        SipMethod method,
        SipUri requestUri,
        SipVersion version,
        SipHeaders headers,
        SipBody body
) implements SipMessage {

    /**
     * Validates and creates a complete request.
     *
     * @throws NullPointerException if any component is {@code null}
     */
    public SipRequest {
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(requestUri, "requestUri");
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(headers, "headers");
        Objects.requireNonNull(body, "body");
    }

    /**
     * Creates an empty-body SIP/2.0 request.
     *
     * @param method request method
     * @param requestUri request target
     * @param headers immutable ordered headers
     * @throws NullPointerException if any argument is {@code null}
     */
    public SipRequest(SipMethod method, SipUri requestUri, SipHeaders headers) {
        this(method, requestUri, SipVersion.SIP_2_0, headers, SipBody.empty());
    }
}
