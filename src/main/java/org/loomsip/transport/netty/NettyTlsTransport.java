package org.loomsip.transport.netty;

import org.loomsip.codec.SipParserLimits;
import org.loomsip.transport.SipMessageHandler;
import org.loomsip.transport.SipTransport;

import java.util.Objects;

/**
 * Netty TLS transport backed by the shared reliable-stream lifecycle.
 *
 * <pre>{@code
 * SipMessage
 *     |
 *     v
 * ConnectionManager --> one TLS channel per security-aware ConnectionKey
 *     |
 *     v
 * SslHandler handshake --> SipStreamDecoder --> SipMessageHandler
 * }</pre>
 *
 * <p>The TLS handler is inserted before SIP framing. No SIP bytes are decoded
 * or sent until the TLS handshake succeeds, and handshake failures never fall
 * back to plain TCP.</p>
 */
public final class NettyTlsTransport extends NettyTcpTransport implements SipTransport {

    /**
     * Creates a TLS transport with default complete-message parser limits.
     *
     * @param config TLS listener, certificate, and verification configuration
     * @param handler decoded message and transport diagnostic callback
     */
    public NettyTlsTransport(TlsTransportConfig config, SipMessageHandler handler) {
        this(config, SipParserLimits.DEFAULT, handler);
    }

    /**
     * Creates a TLS transport with explicit complete-message parser limits.
     *
     * @param config TLS listener, certificate, and verification configuration
     * @param parserLimits complete-message parser limits
     * @param handler decoded message and transport diagnostic callback
     */
    public NettyTlsTransport(
            TlsTransportConfig config,
            SipParserLimits parserLimits,
            SipMessageHandler handler
    ) {
        super(
                org.loomsip.transport.TransportProtocol.TLS,
                Objects.requireNonNull(config, "config").tcpConfig(),
                parserLimits,
                handler,
                config
        );
    }
}
