package org.loomsip.codec;

/**
 * Resource limits applied before a parsed SIP message reaches higher layers.
 *
 * <p>These limits belong to the SIP codec and apply to untrusted inbound
 * messages from every transport, including UDP, TCP, TLS, and WebSocket. They
 * are not UDP-specific. A transport may impose an additional outer limit, such
 * as a maximum UDP datagram size, TCP cumulation size, or WebSocket frame size.</p>
 *
 * <p>Stream transports must enforce the same limits incrementally while
 * locating the header delimiter and waiting for the body. This prevents an
 * oversized start-line, an unterminated header section, or a declared body
 * length from growing the cumulation buffer before a complete message can be
 * passed to {@link SipMessageParser}. The parser then applies the limits again
 * when validating the complete framed message.</p>
 *
 * @param maxStartLineBytes maximum encoded start-line length, excluding CRLF
 * @param maxHeaderBytes maximum encoded header section length
 * @param maxBodyBytes maximum binary body length; may be zero
 */
public record SipParserLimits(
        int maxStartLineBytes,
        int maxHeaderBytes,
        int maxBodyBytes
) {

    /** Default limits: 4 KiB start-line, 64 KiB headers, and 1 MiB body. */
    public static final SipParserLimits DEFAULT = new SipParserLimits(4_096, 65_536, 1_048_576);

    /**
     * Validates and creates parser resource limits.
     *
     * @throws IllegalArgumentException if a line/header limit is not positive
     *                                  or the body limit is negative
     */
    public SipParserLimits {
        if (maxStartLineBytes <= 0 || maxHeaderBytes <= 0 || maxBodyBytes < 0) {
            throw new IllegalArgumentException("parser limits must be positive (body may be zero)");
        }
    }
}
