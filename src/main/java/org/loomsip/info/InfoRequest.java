package org.loomsip.info;

import org.loomsip.message.SipBody;
import org.loomsip.message.SipHeaders;
import org.loomsip.message.header.InfoPackageHeaderValue;

import java.util.Objects;

/**
 * Immutable application payload for one packaged RFC 6086 INFO request.
 *
 * <p>The package token is modeled separately from the original immutable SIP
 * headers. The model does not interpret {@code Content-Type} or the body.</p>
 *
 * @param infoPackage INFO package selected by the application
 * @param headers immutable INFO request headers
 * @param body opaque INFO body
 */
public record InfoRequest(
        InfoPackageHeaderValue infoPackage,
        SipHeaders headers,
        SipBody body
) {

    /** Validates the immutable request payload. */
    public InfoRequest {
        Objects.requireNonNull(infoPackage, "infoPackage");
        Objects.requireNonNull(headers, "headers");
        Objects.requireNonNull(body, "body");
    }
}
