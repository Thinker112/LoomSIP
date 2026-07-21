package org.loomsip.info;

import org.loomsip.message.SipBody;
import org.loomsip.message.SipHeaders;

import java.util.Objects;
import java.util.Set;

/**
 * Immutable final response selected by an INFO application handler.
 *
 * <p>The NIST/bridge creates transaction-managed response headers. The handler
 * supplies only a final status, reason phrase, additional headers, and body.</p>
 *
 * @param statusCode final SIP response status code
 * @param reasonPhrase response reason phrase
 * @param additionalHeaders application response headers
 * @param body opaque response body
 */
public record InfoResponse(
        int statusCode,
        String reasonPhrase,
        SipHeaders additionalHeaders,
        SipBody body
) {

    private static final Set<String> TRANSACTION_HEADERS = Set.of(
            "Via", "From", "To", "Call-ID", "CSeq", "Content-Length"
    );

    /** Validates one final SIP response and its immutable application payload. */
    public InfoResponse {
        if (statusCode < 200 || statusCode > 699) {
            throw new IllegalArgumentException("INFO response must use a final SIP status code");
        }
        if (Objects.requireNonNull(reasonPhrase, "reasonPhrase").isBlank()) {
            throw new IllegalArgumentException("reasonPhrase must not be blank");
        }
        Objects.requireNonNull(additionalHeaders, "additionalHeaders");
        Objects.requireNonNull(body, "body");
        for (String header : TRANSACTION_HEADERS) {
            if (additionalHeaders.contains(header)) {
                throw new IllegalArgumentException("INFO response cannot override transaction header " + header);
            }
        }
    }

    /**
     * Creates an empty successful INFO response.
     *
     * @return 200 OK response model
     */
    public static InfoResponse ok() {
        return new InfoResponse(200, "OK", SipHeaders.empty(), SipBody.empty());
    }
}
