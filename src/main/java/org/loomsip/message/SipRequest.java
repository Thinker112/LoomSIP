package org.loomsip.message;

import java.util.Objects;

public record SipRequest(
        SipMethod method,
        SipUri requestUri,
        SipVersion version,
        SipHeaders headers,
        SipBody body
) implements SipMessage {

    public SipRequest {
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(requestUri, "requestUri");
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(headers, "headers");
        Objects.requireNonNull(body, "body");
    }

    public SipRequest(SipMethod method, SipUri requestUri, SipHeaders headers) {
        this(method, requestUri, SipVersion.SIP_2_0, headers, SipBody.empty());
    }
}
