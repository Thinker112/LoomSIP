package org.loomsip.transport.netty;

import java.net.InetSocketAddress;
import java.util.Objects;

/**
 * Configuration specific to one Netty UDP listener.
 *
 * @param bindAddress resolved local address; port zero requests an ephemeral port
 * @param maxDatagramBytes maximum accepted or emitted UDP payload size
 */
public record UdpTransportConfig(
        InetSocketAddress bindAddress,
        int maxDatagramBytes
) {

    /** Maximum theoretical payload of an IPv4 UDP datagram. */
    public static final int MAX_UDP_PAYLOAD_BYTES = 65_507;

    /** Default datagram limit equal to the theoretical UDP payload maximum. */
    public static final int DEFAULT_MAX_DATAGRAM_BYTES = MAX_UDP_PAYLOAD_BYTES;

    /**
     * Validates and creates UDP transport configuration.
     *
     * @throws NullPointerException if {@code bindAddress} is {@code null}
     * @throws IllegalArgumentException if the address is unresolved or the limit
     *                                  falls outside the UDP payload range
     */
    public UdpTransportConfig {
        Objects.requireNonNull(bindAddress, "bindAddress");
        if (bindAddress.isUnresolved()) {
            throw new IllegalArgumentException("UDP bind address must be resolved");
        }
        if (maxDatagramBytes <= 0 || maxDatagramBytes > MAX_UDP_PAYLOAD_BYTES) {
            throw new IllegalArgumentException(
                    "maxDatagramBytes must be between 1 and " + MAX_UDP_PAYLOAD_BYTES
            );
        }
    }

    /**
     * Creates configuration using the default datagram limit.
     *
     * @param bindAddress resolved local bind address
     */
    public UdpTransportConfig(InetSocketAddress bindAddress) {
        this(bindAddress, DEFAULT_MAX_DATAGRAM_BYTES);
    }
}
