package org.loomsip.stack;

import org.loomsip.transport.TransportRegistry;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Lifecycle-only Stack implementation used before protocol component assembly.
 *
 * <pre>{@code
 * start() --> TransportRegistry.start() --> RUNNING
 * close() --> TransportRegistry.close() --> StackResources.close() --> CLOSED
 * }</pre>
 *
 * <p>The monitor serializes Stack state transitions. It intentionally holds
 * no protocol state and never replaces component-level Mailbox ownership.</p>
 */
final class DefaultLoomSipStack implements LoomSipStack {

    private final Object monitor = new Object();
    private final SipStackConfig config;
    private final StackResources resources;
    private final TransportRegistry transportRegistry;
    private final CompletableFuture<Void> started = new CompletableFuture<>();
    private final CompletableFuture<Void> closed = new CompletableFuture<>();
    private SipStackState state = SipStackState.NEW;

    DefaultLoomSipStack(SipStackConfig config, StackResources resources, TransportRegistry transportRegistry) {
        this.config = Objects.requireNonNull(config, "config");
        this.resources = Objects.requireNonNull(resources, "resources");
        this.transportRegistry = Objects.requireNonNull(transportRegistry, "transportRegistry");
    }

    @Override
    public SipStackState state() {
        synchronized (monitor) {
            return state;
        }
    }

    @Override
    public CompletionStage<Void> start() {
        synchronized (monitor) {
            switch (state) {
                case NEW -> {
                    state = SipStackState.STARTING;
                    try {
                        // Keep start and close mutually exclusive while transports bind synchronously.
                        transportRegistry.start();
                        state = SipStackState.RUNNING;
                        started.complete(null);
                    } catch (Throwable cause) {
                        state = SipStackState.FAILED;
                        started.completeExceptionally(cause);
                        closeResourcesAfterStartFailure(cause);
                    }
                }
                case STARTING, RUNNING -> { }
                case FAILED -> { }
                case CLOSING, CLOSED -> {
                    return CompletableFuture.failedFuture(new IllegalStateException("SIP Stack is closed"));
                }
            }
            return started.minimalCompletionStage();
        }
    }

    @Override
    public CompletionStage<Void> closeAsync() {
        synchronized (monitor) {
            if (state == SipStackState.CLOSED || state == SipStackState.CLOSING) {
                return closed.minimalCompletionStage();
            }
            state = SipStackState.CLOSING;
            RuntimeException failure = null;
            try {
                transportRegistry.close();
            } catch (RuntimeException exception) {
                failure = exception;
            }
            try {
                resources.close();
            } catch (RuntimeException exception) {
                if (failure == null) {
                    failure = exception;
                } else {
                    failure.addSuppressed(exception);
                }
            }
            if (failure == null) {
                state = SipStackState.CLOSED;
                closed.complete(null);
            } else {
                state = SipStackState.FAILED;
                closed.completeExceptionally(failure);
            }
            return closed.minimalCompletionStage();
        }
    }

    @Override
    public void close() {
        try {
            closeAsync().toCompletableFuture().get(config.shutdownTimeout().toNanos(), TimeUnit.NANOSECONDS);
            if (!resources.awaitOwnedExecutorTermination(config.shutdownTimeout())) {
                throw new IllegalStateException("SIP Stack shutdown timed out");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while closing SIP Stack", exception);
        } catch (TimeoutException exception) {
            throw new IllegalStateException("SIP Stack shutdown timed out", exception);
        } catch (java.util.concurrent.ExecutionException exception) {
            throw new IllegalStateException("SIP Stack shutdown failed", exception.getCause());
        }
    }

    private void closeResourcesAfterStartFailure(Throwable startFailure) {
        try {
            resources.close();
        } catch (RuntimeException closeFailure) {
            startFailure.addSuppressed(closeFailure);
        }
    }
}
