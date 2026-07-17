package org.loomsip.transport;

import java.time.Duration;
import java.util.Objects;

/**
 * Resource and timeout limits shared by reliable transport connections.
 *
 * @param maxConnections maximum active plus connecting channels
 * @param maxConnectionsPerRemoteAddress maximum channels for one remote IP
 * @param maxPendingConnects maximum simultaneous outbound connect attempts
 * @param connectTimeout maximum duration of one connect attempt
 * @param idleTimeout maximum duration without reads or writes
 */
public record ConnectionLimits(
        int maxConnections,
        int maxConnectionsPerRemoteAddress,
        int maxPendingConnects,
        Duration connectTimeout,
        Duration idleTimeout
) {

    /** Conservative defaults for one transport instance. */
    public static final ConnectionLimits DEFAULT = new ConnectionLimits(
            1_024,
            64,
            128,
            Duration.ofSeconds(10),
            Duration.ofMinutes(2)
    );

    /** Validates connection counts and timeouts. */
    public ConnectionLimits {
        Objects.requireNonNull(connectTimeout, "connectTimeout");
        Objects.requireNonNull(idleTimeout, "idleTimeout");
        if (maxConnections <= 0
                || maxConnectionsPerRemoteAddress <= 0
                || maxPendingConnects <= 0) {
            throw new IllegalArgumentException("connection limits must be positive");
        }
        if (maxConnectionsPerRemoteAddress > maxConnections) {
            throw new IllegalArgumentException(
                    "maxConnectionsPerRemoteAddress must not exceed maxConnections"
            );
        }
        if (connectTimeout.isZero() || connectTimeout.isNegative()) {
            throw new IllegalArgumentException("connectTimeout must be positive");
        }
        if (idleTimeout.isZero() || idleTimeout.isNegative()) {
            throw new IllegalArgumentException("idleTimeout must be positive");
        }
        if (connectTimeout.toMillis() > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("connectTimeout exceeds Netty millisecond range");
        }
    }

    /**
     * Returns the connect timeout in Netty's integer millisecond format.
     *
     * @return connect timeout in milliseconds
     */
    public int connectTimeoutMillis() {
        return Math.toIntExact(connectTimeout.toMillis());
    }
}
