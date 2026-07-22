package org.loomsip.transport;

import org.loomsip.message.SipMessage;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Owns the configured UDP, TCP, and TLS transport instances.
 *
 * <pre>{@code
 * TransportEndpoint.protocol
 *             |
 *             v
 *      +-------------+
 *      | Registry    |
 *      +------+------+ 
 *             |
 *       +-----+-----+-----+
 *       v           v     v
 *      UDP         TCP   TLS
 * }</pre>
 *
 * <p>The registry is only a lifecycle and lookup boundary. It does not select
 * DNS routes, correlate SIP responses, or implement transaction timers.</p>
 */
public final class TransportRegistry implements AutoCloseable {

    private final Object monitor = new Object();
    private final EnumMap<TransportProtocol, SipTransport> transports =
            new EnumMap<>(TransportProtocol.class);
    private boolean started;
    private boolean closed;

    /** Creates an empty registry that can be configured before start. */
    public TransportRegistry() {
    }

    /**
     * Registers one transport before the registry is started.
     *
     * @param protocol protocol provided by the transport
     * @param transport transport instance owned by this registry
     */
    public void register(TransportProtocol protocol, SipTransport transport) {
        Objects.requireNonNull(protocol, "protocol");
        Objects.requireNonNull(transport, "transport");
        synchronized (monitor) {
            if (started || closed) {
                throw new IllegalStateException("transport registry is no longer configurable");
            }
            if (transports.putIfAbsent(protocol, transport) != null) {
                throw new IllegalArgumentException("transport already registered for " + protocol);
            }
        }
    }

    /**
     * Starts all registered transports in protocol order.
     *
     * @throws TransportException if a transport cannot start; already-started
     *                            transports are closed before the exception escapes
     */
    public void start() throws TransportException {
        Map<TransportProtocol, SipTransport> snapshot;
        synchronized (monitor) {
            if (closed) {
                throw new TransportException("transport registry is closed");
            }
            if (started) {
                throw new TransportException("transport registry has already started");
            }
            snapshot = new EnumMap<>(transports);
            started = true;
        }
        try {
            for (SipTransport transport : snapshot.values()) {
                transport.start();
            }
        } catch (TransportException exception) {
            closeTransports(snapshot);
            synchronized (monitor) {
                closed = true;
            }
            throw exception;
        } catch (Throwable cause) {
            closeTransports(snapshot);
            synchronized (monitor) {
                closed = true;
            }
            throw new TransportException("failed to start transport registry", cause);
        }
    }

    /**
     * Returns the registered transport for a protocol.
     *
     * @param protocol requested protocol
     * @return registered transport
     */
    public SipTransport transport(TransportProtocol protocol) {
        Objects.requireNonNull(protocol, "protocol");
        synchronized (monitor) {
            SipTransport transport = transports.get(protocol);
            if (transport == null) {
                throw new IllegalArgumentException("no transport registered for " + protocol);
            }
            return transport;
        }
    }

    /**
     * Returns an immutable snapshot of registered transports.
     *
     * @return protocol-to-transport snapshot
     */
    public Map<TransportProtocol, SipTransport> transports() {
        synchronized (monitor) {
            return Map.copyOf(transports);
        }
    }

    /**
     * Routes one message to the transport matching its target protocol.
     *
     * @param message immutable SIP message
     * @param target resolved transport target
     * @return asynchronous transport write result
     */
    public CompletionStage<SendResult> send(SipMessage message, TransportEndpoint target) {
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(target, "target");
        synchronized (monitor) {
            if (!started || closed) {
                return CompletableFuture.failedFuture(new TransportException(
                        "transport registry is not running"
                ));
            }
        }
        final SipTransport transport;
        try {
            transport = transport(target.protocol());
        } catch (Throwable cause) {
            return CompletableFuture.failedFuture(cause);
        }
        return transport.send(message, target);
    }

    /**
     * Returns whether the registry has completed its start operation.
     *
     * @return {@code true} while the registry accepts sends
     */
    public boolean isStarted() {
        synchronized (monitor) {
            return started && !closed;
        }
    }

    /** Closes every registered transport exactly once. */
    @Override
    public void close() {
        Map<TransportProtocol, SipTransport> snapshot;
        synchronized (monitor) {
            if (closed) {
                return;
            }
            closed = true;
            snapshot = new EnumMap<>(transports);
        }
        closeTransports(snapshot);
    }

    private static void closeTransports(Map<TransportProtocol, SipTransport> transports) {
        transports.values().forEach(transport -> {
            try {
                transport.close();
            } catch (Throwable ignored) {
                // Continue closing the remaining protocol transports.
            }
        });
    }
}
