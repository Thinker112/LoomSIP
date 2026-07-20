package org.loomsip.transport.netty;

import io.netty.handler.ssl.SslContext;
import org.loomsip.codec.StreamBufferLimits;
import org.loomsip.transport.ConnectionLimits;
import org.loomsip.transport.WriteQueueLimits;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * TLS listener, client identity, peer-verification, and handshake configuration.
 *
 * <p>Certificates, private keys, and trust material are supplied through the
 * already-built Netty {@link SslContext} objects. This configuration does not
 * expose them in {@link #toString()}.</p>
 *
 * @param bindAddress resolved local listener address; port zero requests an ephemeral port
 * @param streamBufferLimits incremental stream framing limits per connection
 * @param connectionLimits connection counts and idle/connect timeout limits
 * @param writeQueueLimits per-connection pending write limits
 * @param serverContext server-mode TLS context for accepted channels
 * @param clientContext client-mode TLS context for outbound channels
 * @param handshakeTimeout maximum TLS handshake duration
 * @param hostnameVerification whether client engines verify the peer hostname
 * @param securityProfile stable identity used in connection reuse keys
 * @param enabledProtocols TLS protocol allow-list, empty to use context defaults
 * @param enabledCipherSuites TLS cipher allow-list, empty to use context defaults
 */
public record TlsTransportConfig(
        InetSocketAddress bindAddress,
        StreamBufferLimits streamBufferLimits,
        ConnectionLimits connectionLimits,
        WriteQueueLimits writeQueueLimits,
        SslContext serverContext,
        SslContext clientContext,
        Duration handshakeTimeout,
        boolean hostnameVerification,
        String securityProfile,
        List<String> enabledProtocols,
        List<String> enabledCipherSuites
) {

    /**
     * Creates a TLS configuration with default stream, connection, and engine settings.
     *
     * @param bindAddress resolved local listener address
     * @param serverContext server-mode TLS context
     * @param clientContext client-mode TLS context
     */
    public TlsTransportConfig(
            InetSocketAddress bindAddress,
            SslContext serverContext,
            SslContext clientContext
    ) {
        this(
                bindAddress,
                StreamBufferLimits.DEFAULT,
                ConnectionLimits.DEFAULT,
                WriteQueueLimits.DEFAULT,
                serverContext,
                clientContext,
                Duration.ofSeconds(10),
                true,
                "default",
                List.of(),
                List.of()
        );
    }

    /**
     * Creates TLS configuration with default write queue limits.
     *
     * @param bindAddress resolved local listener address
     * @param streamBufferLimits incremental framing limits
     * @param connectionLimits connection counts and timeouts
     * @param serverContext server-mode TLS context
     * @param clientContext client-mode TLS context
     * @param handshakeTimeout maximum handshake duration
     * @param hostnameVerification whether to verify client peer names
     * @param securityProfile connection reuse security profile
     * @param enabledProtocols TLS protocol allow-list
     * @param enabledCipherSuites TLS cipher allow-list
     */
    public TlsTransportConfig(
            InetSocketAddress bindAddress,
            StreamBufferLimits streamBufferLimits,
            ConnectionLimits connectionLimits,
            SslContext serverContext,
            SslContext clientContext,
            Duration handshakeTimeout,
            boolean hostnameVerification,
            String securityProfile,
            List<String> enabledProtocols,
            List<String> enabledCipherSuites
    ) {
        this(
                bindAddress,
                streamBufferLimits,
                connectionLimits,
                WriteQueueLimits.DEFAULT,
                serverContext,
                clientContext,
                handshakeTimeout,
                hostnameVerification,
                securityProfile,
                enabledProtocols,
                enabledCipherSuites
        );
    }

    /** Validates TLS configuration and defensively copies protocol lists. */
    public TlsTransportConfig {
        Objects.requireNonNull(bindAddress, "bindAddress");
        if (bindAddress.isUnresolved()) {
            throw new IllegalArgumentException("TLS bind address must be resolved");
        }
        Objects.requireNonNull(streamBufferLimits, "streamBufferLimits");
        Objects.requireNonNull(connectionLimits, "connectionLimits");
        Objects.requireNonNull(writeQueueLimits, "writeQueueLimits");
        Objects.requireNonNull(serverContext, "serverContext");
        Objects.requireNonNull(clientContext, "clientContext");
        Objects.requireNonNull(handshakeTimeout, "handshakeTimeout");
        Objects.requireNonNull(securityProfile, "securityProfile");
        Objects.requireNonNull(enabledProtocols, "enabledProtocols");
        Objects.requireNonNull(enabledCipherSuites, "enabledCipherSuites");
        if (handshakeTimeout.isZero() || handshakeTimeout.isNegative()) {
            throw new IllegalArgumentException("handshakeTimeout must be positive");
        }
        if (handshakeTimeout.toMillis() > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("handshakeTimeout exceeds Netty millisecond range");
        }
        if (securityProfile.isBlank()) {
            throw new IllegalArgumentException("securityProfile must not be blank");
        }
        enabledProtocols = List.copyOf(enabledProtocols);
        enabledCipherSuites = List.copyOf(enabledCipherSuites);
    }

    /** Creates the common stream transport portion used by the TCP implementation. */
    TcpTransportConfig tcpConfig() {
        return new TcpTransportConfig(bindAddress, streamBufferLimits, connectionLimits, writeQueueLimits);
    }

    /**
     * Returns the configured handshake timeout in Netty's integer millisecond format.
     *
     * @return handshake timeout in milliseconds
     */
    public int handshakeTimeoutMillis() {
        return Math.toIntExact(handshakeTimeout.toMillis());
    }

    /** Omits TLS contexts and key material from diagnostic text. */
    @Override
    public String toString() {
        return "TlsTransportConfig[bindAddress=" + bindAddress
                + ", handshakeTimeout=" + handshakeTimeout
                + ", hostnameVerification=" + hostnameVerification
                + ", securityProfile=" + securityProfile
                + ", enabledProtocols=" + enabledProtocols
                + ", enabledCipherSuites=" + enabledCipherSuites + "]";
    }
}
