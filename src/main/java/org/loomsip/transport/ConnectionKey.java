package org.loomsip.transport;

import java.net.InetSocketAddress;
import java.util.Objects;

/**
 * Identity used to merge concurrent connects and reuse reliable connections.
 *
 * <p>The local address is a bind identity, not necessarily the ephemeral local
 * address selected for an outbound socket. TLS will extend this identity with
 * security-profile and target-name information in stage 5C.</p>
 *
 * @param protocol reliable transport protocol
 * @param localBindAddress configured local bind identity
 * @param remoteAddress resolved peer address
 */
public record ConnectionKey(
        TransportProtocol protocol,
        InetSocketAddress localBindAddress,
        InetSocketAddress remoteAddress
) {

    /** Validates connection identity components. */
    public ConnectionKey {
        Objects.requireNonNull(protocol, "protocol");
        Objects.requireNonNull(localBindAddress, "localBindAddress");
        Objects.requireNonNull(remoteAddress, "remoteAddress");
        if (protocol == TransportProtocol.UDP) {
            throw new IllegalArgumentException("connection key requires a reliable transport");
        }
        if (localBindAddress.isUnresolved() || remoteAddress.isUnresolved()) {
            throw new IllegalArgumentException("connection addresses must be resolved");
        }
        if (remoteAddress.getPort() == 0) {
            throw new IllegalArgumentException("remote connection port must not be zero");
        }
    }
}
