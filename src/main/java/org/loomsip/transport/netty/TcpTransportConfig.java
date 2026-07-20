package org.loomsip.transport.netty;

import org.loomsip.codec.StreamBufferLimits;
import org.loomsip.transport.ConnectionLimits;
import org.loomsip.transport.WriteQueueLimits;

import java.net.InetSocketAddress;
import java.util.Objects;

/**
 * Configuration of one Netty TCP listener and its managed child connections.
 *
 * @param bindAddress resolved listener address; port zero requests an ephemeral port
 * @param streamBufferLimits incremental stream framing limits per connection
 * @param connectionLimits connection counts and timeout limits
 * @param writeQueueLimits per-connection pending write limits
 */
public record TcpTransportConfig(
        InetSocketAddress bindAddress,
        StreamBufferLimits streamBufferLimits,
        ConnectionLimits connectionLimits,
        WriteQueueLimits writeQueueLimits
) {

    /** Validates listener and connection configuration. */
    public TcpTransportConfig {
        Objects.requireNonNull(bindAddress, "bindAddress");
        Objects.requireNonNull(streamBufferLimits, "streamBufferLimits");
        Objects.requireNonNull(connectionLimits, "connectionLimits");
        Objects.requireNonNull(writeQueueLimits, "writeQueueLimits");
        if (bindAddress.isUnresolved()) {
            throw new IllegalArgumentException("TCP bind address must be resolved");
        }
    }

    /**
     * Creates TCP configuration with default framing and connection limits.
     *
     * @param bindAddress resolved local listener address
     */
    public TcpTransportConfig(InetSocketAddress bindAddress) {
        this(bindAddress, StreamBufferLimits.DEFAULT, ConnectionLimits.DEFAULT, WriteQueueLimits.DEFAULT);
    }

    /**
     * Creates TCP configuration with explicit connection limits.
     *
     * @param bindAddress resolved local listener address
     * @param connectionLimits connection counts and timeout limits
     */
    public TcpTransportConfig(InetSocketAddress bindAddress, ConnectionLimits connectionLimits) {
        this(bindAddress, StreamBufferLimits.DEFAULT, connectionLimits, WriteQueueLimits.DEFAULT);
    }

    /**
     * Creates TCP configuration with explicit stream and connection limits,
     * using default write queue limits.
     *
     * @param bindAddress resolved local listener address
     * @param streamBufferLimits incremental framing limits
     * @param connectionLimits connection counts and timeouts
     */
    public TcpTransportConfig(
            InetSocketAddress bindAddress,
            StreamBufferLimits streamBufferLimits,
            ConnectionLimits connectionLimits
    ) {
        this(bindAddress, streamBufferLimits, connectionLimits, WriteQueueLimits.DEFAULT);
    }
}
