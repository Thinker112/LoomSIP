package org.loomsip.transport;

import java.net.InetSocketAddress;
import java.util.Objects;

/**
 * Protocol and socket address identifying a local listener or remote target.
 *
 * @param protocol network transport protocol
 * @param address IP socket address; unresolved addresses are rejected
 */
public record TransportEndpoint(
        TransportProtocol protocol,
        InetSocketAddress address
) {

    /**
     * Validates and creates a transport endpoint.
     *
     * @throws NullPointerException if a component is {@code null}
     * @throws IllegalArgumentException if {@code address} is unresolved
     */
    public TransportEndpoint {
        Objects.requireNonNull(protocol, "protocol");
        Objects.requireNonNull(address, "address");
        if (address.isUnresolved()) {
            throw new IllegalArgumentException("transport endpoint address must be resolved");
        }
    }

    /**
     * Creates a UDP endpoint.
     *
     * @param address resolved UDP socket address
     * @return UDP endpoint
     */
    public static TransportEndpoint udp(InetSocketAddress address) {
        return new TransportEndpoint(TransportProtocol.UDP, address);
    }
}
