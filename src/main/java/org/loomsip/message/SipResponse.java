package org.loomsip.message;

import java.util.Objects;

public record SipResponse(
        SipVersion version,
        int statusCode,
        String reasonPhrase,
        SipHeaders headers,
        SipBody body
) implements SipMessage {

    public SipResponse {
        Objects.requireNonNull(version, "version");
        if (statusCode < 100 || statusCode > 699) {
            throw new IllegalArgumentException("SIP status code must be between 100 and 699");
        }
        SipSyntax.requireSingleLine(reasonPhrase, "reason phrase");
        Objects.requireNonNull(headers, "headers");
        Objects.requireNonNull(body, "body");
    }

    public SipResponse(int statusCode, String reasonPhrase, SipHeaders headers) {
        this(SipVersion.SIP_2_0, statusCode, reasonPhrase, headers, SipBody.empty());
    }
}
