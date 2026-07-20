package org.loomsip.transport;

import java.net.InetSocketAddress;
import java.util.Objects;

/**
 * Identity used to merge concurrent connects and reuse reliable connections.
 *
 * <p>The local address is a bind identity, not necessarily the ephemeral local
 * address selected for an outbound socket. TLS uses the security profile and
 * peer identity (typically SNI/hostname) to prevent unsafe cross-identity reuse.</p>
 *
 * @param protocol reliable transport protocol
 * @param localBindAddress configured local bind identity
 * @param remoteAddress resolved peer address
 * @param securityProfile TLS security profile, or empty for plain TCP
 * @param peerIdentity TLS peer identity/SNI, or empty for accepted channels
 */
public record ConnectionKey(
        TransportProtocol protocol,
        InetSocketAddress localBindAddress,
        InetSocketAddress remoteAddress,
        String securityProfile,
        String peerIdentity
) {

    /** Validates connection identity components. */
    public ConnectionKey {
        Objects.requireNonNull(protocol, "protocol");
        Objects.requireNonNull(localBindAddress, "localBindAddress");
        Objects.requireNonNull(remoteAddress, "remoteAddress");
        Objects.requireNonNull(securityProfile, "securityProfile");
        Objects.requireNonNull(peerIdentity, "peerIdentity");
        if (protocol == TransportProtocol.UDP) {
            throw new IllegalArgumentException("connection key requires a reliable transport");
        }
        if (localBindAddress.isUnresolved() || remoteAddress.isUnresolved()) {
            throw new IllegalArgumentException("connection addresses must be resolved");
        }
        if (remoteAddress.getPort() == 0) {
            throw new IllegalArgumentException("remote connection port must not be zero");
        }
        if (securityProfile.isBlank() && protocol == TransportProtocol.TLS) {
            throw new IllegalArgumentException("TLS connection key requires a security profile");
        }
    }

    /**
     * Creates a plain reliable-transport key with no TLS identity fields.
     *
     * @param protocol TCP or TLS protocol
     * @param localBindAddress configured local bind identity
     * @param remoteAddress resolved peer address
     */
    public ConnectionKey(
            TransportProtocol protocol,
            InetSocketAddress localBindAddress,
            InetSocketAddress remoteAddress
    ) {
        this(
                protocol,
                localBindAddress,
                remoteAddress,
                protocol == TransportProtocol.TLS ? "default" : "",
                ""
        );
    }
}
