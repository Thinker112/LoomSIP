package org.loomsip.transport.netty;

import org.loomsip.transport.ConnectionKey;
import org.loomsip.transport.ConnectionLimits;
import org.loomsip.transport.TransportConnection;
import org.loomsip.transport.TransportConnectionId;
import org.loomsip.transport.TransportLimitException;
import org.loomsip.transport.TransportException;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Merges outbound connects and owns all active TCP connection registrations.
 *
 * <pre>{@code
 * send A --+
 * send B --+--> ConnectionKey --> one pending connect --> active connection
 * send C --+                              |
 *                                            +--> all waiters share failure
 *
 * accepted channel --> registerInbound --> active connection registry
 * channel close    --> remove key/id     --> pending writes fail
 * }</pre>
 *
 * <p>The manager serializes only registry accounting. Netty still owns channel
 * I/O and higher SIP layers still own Transaction/Dialog ordering.</p>
 */
public final class ConnectionManager implements AutoCloseable {

    private final ConnectionLimits limits;
    private final Object monitor = new Object();
    private final ConcurrentHashMap<ConnectionKey, CompletableFuture<NettyTransportConnection>>
            connecting = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<ConnectionKey, NettyTransportConnection> activeByKey =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<TransportConnectionId, NettyTransportConnection> activeById =
            new ConcurrentHashMap<>();

    private int pendingConnects;
    private boolean closed;

    /**
     * Creates a manager with explicit limits.
     *
     * @param limits connection counts and timeout limits
     */
    public ConnectionManager(ConnectionLimits limits) {
        this.limits = Objects.requireNonNull(limits, "limits");
    }

    CompletionStage<NettyTransportConnection> acquire(
            ConnectionKey key,
            Supplier<? extends CompletionStage<NettyTransportConnection>> connector
    ) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(connector, "connector");

        Optional<NettyTransportConnection> reusable = reusableConnection(key);
        if (reusable.isPresent()) {
            return CompletableFuture.completedFuture(reusable.orElseThrow());
        }

        CompletableFuture<NettyTransportConnection> promise = new CompletableFuture<>();
        CompletableFuture<NettyTransportConnection> existing = connecting.putIfAbsent(key, promise);
        if (existing != null) {
            return existing;
        }

        // A connect may have completed between the first reuse lookup and this
        // thread winning the pending-connect slot. Recheck before reserving or
        // starting a second physical connection.
        reusable = reusableConnection(key);
        if (reusable.isPresent()) {
            connecting.remove(key, promise);
            promise.complete(reusable.orElseThrow());
            return promise;
        }

        TransportException rejected = reserveConnect(key);
        if (rejected != null) {
            connecting.remove(key, promise);
            promise.completeExceptionally(rejected);
            return promise;
        }

        final CompletionStage<NettyTransportConnection> connectStage;
        try {
            connectStage = Objects.requireNonNull(connector.get(), "connector result");
        } catch (Throwable cause) {
            completeConnect(key, promise, null, cause);
            return promise;
        }
        connectStage.whenComplete((connection, failure) ->
                completeConnect(key, promise, connection, failure)
        );
        return promise;
    }

    Optional<NettyTransportConnection> reusableConnection(ConnectionKey key) {
        Objects.requireNonNull(key, "key");
        return activeById.values().stream()
                .filter(NettyTransportConnection::isReusable)
                .filter(connection -> connection.remoteEndpoint().address().equals(key.remoteAddress()))
                .filter(connection -> connection.key().securityProfile().equals(key.securityProfile()))
                .filter(connection -> connection.key().peerIdentity().isBlank()
                        || key.peerIdentity().isBlank()
                        || connection.key().peerIdentity().equalsIgnoreCase(key.peerIdentity()))
                .findFirst();
    }

    boolean registerInbound(ConnectionKey key, NettyTransportConnection connection) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(connection, "connection");
        synchronized (monitor) {
            if (closed || exceedsConnectionLimit(key.remoteAddress())) {
                return false;
            }
            registerActive(key, connection);
            return true;
        }
    }

    Throwable channelClosed(NettyTransportConnection connection) {
        Objects.requireNonNull(connection, "connection");
        synchronized (monitor) {
            activeById.remove(connection.id(), connection);
            activeByKey.remove(connection.key(), connection);
        }
        return connection.channelClosed(new TransportException(
                "TCP connection closed by peer " + connection.remoteEndpoint().address()
        ));
    }

    /**
     * Returns the number of currently active accepted and outbound connections.
     *
     * @return active connection count
     */
    public int activeConnectionCount() {
        return activeById.size();
    }

    /**
     * Returns the number of outbound connection attempts in progress.
     *
     * @return pending connect count
     */
    public int pendingConnectCount() {
        synchronized (monitor) {
            return pendingConnects;
        }
    }

    /**
     * Returns transport-neutral snapshots of active connections.
     *
     * @return immutable active connection snapshot
     */
    public List<TransportConnection> connections() {
        return List.copyOf(activeById.values());
    }

    @Override
    public void close() {
        TransportException failure = new TransportException("TCP connection manager closed");
        List<NettyTransportConnection> connections;
        synchronized (monitor) {
            if (closed) {
                return;
            }
            closed = true;
            connecting.values().forEach(future -> future.completeExceptionally(failure));
            connecting.clear();
            pendingConnects = 0;
            connections = List.copyOf(activeById.values());
            activeById.clear();
            activeByKey.clear();
        }
        connections.forEach(connection -> connection.close(failure));
    }

    private TransportException reserveConnect(ConnectionKey key) {
        synchronized (monitor) {
            if (closed) {
                return new TransportException("TCP connection manager is closed");
            }
            if (pendingConnects >= limits.maxPendingConnects()) {
                return new TransportLimitException(
                        "TCP pending connect limit reached: " + limits.maxPendingConnects()
                );
            }
            if (exceedsConnectionLimit(key.remoteAddress())) {
                return new TransportLimitException(
                        "TCP connection limit reached for " + key.remoteAddress().getAddress()
                );
            }
            pendingConnects++;
            return null;
        }
    }

    private boolean exceedsConnectionLimit(InetSocketAddress remoteAddress) {
        if (activeById.size() + pendingConnects >= limits.maxConnections()) {
            return true;
        }
        InetAddress remoteIp = remoteAddress.getAddress();
        long activeForRemote = activeById.values().stream()
                .filter(connection -> connection.remoteEndpoint().address().getAddress().equals(remoteIp))
                .count();
        long pendingForRemote = connecting.keySet().stream()
                .filter(key -> key.remoteAddress().getAddress().equals(remoteIp))
                .count();
        return activeForRemote + pendingForRemote > limits.maxConnectionsPerRemoteAddress();
    }

    private void completeConnect(
            ConnectionKey key,
            CompletableFuture<NettyTransportConnection> promise,
            NettyTransportConnection connection,
            Throwable failure
    ) {
        boolean rejectCompletedConnection = false;
        Throwable completionFailure = failure;
        synchronized (monitor) {
            if (pendingConnects > 0) {
                pendingConnects--;
            }
            if (completionFailure == null && connection != null && !closed) {
                registerActive(key, connection);
                if (!connection.isReusable()) {
                    activeById.remove(connection.id(), connection);
                    activeByKey.remove(key, connection);
                    completionFailure = new TransportException(
                            "TCP channel closed while connect completed"
                    );
                }
            } else if (connection != null) {
                rejectCompletedConnection = true;
            }
            connecting.remove(key, promise);
        }

        if (completionFailure != null) {
            promise.completeExceptionally(asTransportFailure(
                    "failed to connect TCP channel to " + key.remoteAddress(),
                    completionFailure
            ));
        } else if (connection == null) {
            promise.completeExceptionally(new TransportException(
                    "TCP connector completed without a connection"
            ));
        } else if (rejectCompletedConnection) {
            TransportException closedFailure = new TransportException(
                    "TCP connection manager closed while connect completed"
            );
            connection.close(closedFailure);
            promise.completeExceptionally(closedFailure);
        } else {
            promise.complete(connection);
        }
    }

    private void registerActive(ConnectionKey key, NettyTransportConnection connection) {
        activeById.put(connection.id(), connection);
        activeByKey.put(key, connection);
    }

    private static TransportException asTransportFailure(String message, Throwable cause) {
        Throwable actual = cause instanceof java.util.concurrent.CompletionException
                && cause.getCause() != null ? cause.getCause() : cause;
        return actual instanceof TransportException transportException
                ? transportException
                : new TransportException(message, actual);
    }
}
