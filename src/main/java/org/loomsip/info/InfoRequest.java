package org.loomsip.info;

import org.loomsip.message.SipBody;
import org.loomsip.message.SipHeaders;
import org.loomsip.message.header.InfoPackageHeaderValue;

import java.util.Objects;

/**
 * Immutable application payload for one packaged RFC 6086 INFO request.
 *
 * <p>The package token is modeled separately so the Dialog layer can own the
 * resulting {@code Info-Package} header. Applications provide only additional
 * headers, such as {@code Content-Type}, and the opaque body.</p>
 *
 * @param infoPackage INFO package selected by the application
 * @param additionalHeaders application headers excluding Info-Package
 * @param body opaque INFO body
 */
public record InfoRequest(
        InfoPackageHeaderValue infoPackage,
        SipHeaders additionalHeaders,
        SipBody body
) {

    /** Validates the package-owned header boundary and immutable payload. */
    public InfoRequest {
        Objects.requireNonNull(infoPackage, "infoPackage");
        Objects.requireNonNull(additionalHeaders, "additionalHeaders");
        Objects.requireNonNull(body, "body");
        if (additionalHeaders.contains("Info-Package")) {
            throw new IllegalArgumentException("InfoRequest manages the Info-Package header");
        }
    }
}
