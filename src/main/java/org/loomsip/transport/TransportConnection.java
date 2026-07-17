package org.loomsip.transport;

/**
 * Transport-neutral view of one reliable connection.
 *
 * <p>The view exposes connection identity and lifecycle without leaking a
 * Netty {@code Channel} into Transaction, Dialog, or application code.</p>
 */
public interface TransportConnection extends AutoCloseable {

    /**
     * Returns the stable connection identifier.
     *
     * @return connection identifier
     */
    TransportConnectionId id();

    /**
     * Returns the connection-reuse identity.
     *
     * @return connection key
     */
    ConnectionKey key();

    /**
     * Returns the current connection lifecycle state.
     *
     * @return current state
     */
    ConnectionState state();

    /**
     * Returns the actual local socket endpoint.
     *
     * @return local endpoint
     */
    TransportEndpoint localEndpoint();

    /**
     * Returns the actual peer socket endpoint.
     *
     * @return remote endpoint
     */
    TransportEndpoint remoteEndpoint();

    /** Starts an idempotent local close. */
    @Override
    void close();
}
