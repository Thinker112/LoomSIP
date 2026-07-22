package org.loomsip.transport.netty;

import org.loomsip.codec.SipParserLimits;
import org.loomsip.stack.StackTransportFactory;

import java.util.Objects;

/**
 * Factories that adapt immutable Netty Transport configuration to Stack assembly.
 *
 * <pre>{@code
 * Udp/Tcp/TlsTransportConfig --> NettyTransports --> StackTransportFactory
 * }</pre>
 */
public final class NettyTransports {

    private NettyTransports() {
    }

    /**
     * Creates a UDP Transport factory with default parser limits.
     *
     * @param config UDP listener configuration
     * @return unstarted UDP Transport factory
     */
    public static StackTransportFactory udp(UdpTransportConfig config) {
        return udp(config, SipParserLimits.DEFAULT);
    }

    /**
     * Creates a UDP Transport factory with explicit parser limits.
     *
     * @param config UDP listener configuration
     * @param parserLimits complete-message parser limits
     * @return unstarted UDP Transport factory
     */
    public static StackTransportFactory udp(UdpTransportConfig config, SipParserLimits parserLimits) {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(parserLimits, "parserLimits");
        return handler -> new NettyUdpTransport(config, parserLimits, handler);
    }

    /**
     * Creates a TCP Transport factory with default parser limits.
     *
     * @param config TCP listener configuration
     * @return unstarted TCP Transport factory
     */
    public static StackTransportFactory tcp(TcpTransportConfig config) {
        return tcp(config, SipParserLimits.DEFAULT);
    }

    /**
     * Creates a TCP Transport factory with explicit parser limits.
     *
     * @param config TCP listener configuration
     * @param parserLimits complete-message parser limits
     * @return unstarted TCP Transport factory
     */
    public static StackTransportFactory tcp(TcpTransportConfig config, SipParserLimits parserLimits) {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(parserLimits, "parserLimits");
        return handler -> new NettyTcpTransport(config, parserLimits, handler);
    }

    /**
     * Creates a TLS Transport factory with default parser limits.
     *
     * @param config TLS listener and security configuration
     * @return unstarted TLS Transport factory
     */
    public static StackTransportFactory tls(TlsTransportConfig config) {
        return tls(config, SipParserLimits.DEFAULT);
    }

    /**
     * Creates a TLS Transport factory with explicit parser limits.
     *
     * @param config TLS listener and security configuration
     * @param parserLimits complete-message parser limits
     * @return unstarted TLS Transport factory
     */
    public static StackTransportFactory tls(TlsTransportConfig config, SipParserLimits parserLimits) {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(parserLimits, "parserLimits");
        return handler -> new NettyTlsTransport(config, parserLimits, handler);
    }
}
