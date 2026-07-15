package org.loomsip.message;

/**
 * Common immutable representation of a complete SIP request or response.
 */
public sealed interface SipMessage permits SipRequest, SipResponse {

    /**
     * Returns the protocol version from the start-line.
     *
     * @return SIP protocol version
     */
    SipVersion version();

    /**
     * Returns headers in their parsed or constructed order.
     *
     * @return immutable ordered headers
     */
    SipHeaders headers();

    /**
     * Returns the immutable binary message body.
     *
     * @return message body, never {@code null}
     */
    SipBody body();
}
