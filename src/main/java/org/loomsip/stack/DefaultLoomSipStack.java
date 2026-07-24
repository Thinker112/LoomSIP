package org.loomsip.stack;

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
 * start() --> StackTransportAssembly.start() --> RUNNING
 * close() --> StackTransportAssembly.close() --> StackResources.close() --> CLOSED
 * }</pre>
 *
 * <p>The monitor serializes Stack state transitions. It intentionally holds
 * no protocol state and never replaces component-level Mailbox ownership.</p>
 */
final class DefaultLoomSipStack implements LoomSipStack {

    private final Object monitor = new Object();
    private final SipStackConfig config;
    private final StackResources resources;
    private final StackTransportAssembly transportAssembly;
    private final StackTransactionRuntime transactionRuntime;
    private final SipClient client;
    private final SipStackListener listener;
    private final CompletableFuture<Void> started = new CompletableFuture<>();
    private final CompletableFuture<Void> closed = new CompletableFuture<>();
    private SipStackState state = SipStackState.NEW;
    private volatile String lastFailure;

    DefaultLoomSipStack(
            SipStackConfig config,
            StackResources resources,
            StackTransportAssembly transportAssembly,
            StackTransactionRuntime transactionRuntime,
            SipStackListener listener
    ) {
        this.config = Objects.requireNonNull(config, "config");
        this.resources = Objects.requireNonNull(resources, "resources");
        this.transportAssembly = Objects.requireNonNull(transportAssembly, "transportAssembly");
        this.transactionRuntime = Objects.requireNonNull(transactionRuntime, "transactionRuntime");
        this.client = new DefaultSipClient(
                this::state, transactionRuntime.inviteTransactions(), transactionRuntime.nonInviteTransactions()
        );
        this.listener = Objects.requireNonNull(listener, "listener");
    }

    @Override
    public SipStackState state() {
        synchronized (monitor) {
            return state;
        }
    }

    @Override
    public SipClient client() {
        return client;
    }

    @Override
    public StackStateSnapshot snapshot() {
        synchronized (monitor) {
            return snapshotLocked();
        }
    }

    @Override
    public java.util.Optional<org.loomsip.dialog.DialogManager> dialogs() {
        return transactionRuntime.dialogs();
    }
    @Override public java.util.Optional<org.loomsip.subscription.SubscriptionManager> subscriptions() { return transactionRuntime.subscriptions(); }

    @Override
    public CompletionStage<Void> start() {
        synchronized (monitor) {
            switch (state) {
                case NEW -> {
                    state = SipStackState.STARTING;
                    try {
                        // Keep start and close mutually exclusive while transports bind synchronously.
                        transportAssembly.start();
                        state = SipStackState.RUNNING;
                        started.complete(null);
                        notifyState(snapshotLocked());
                    } catch (Throwable cause) {
                        state = SipStackState.FAILED;
                        lastFailure = summary(cause);
                        started.completeExceptionally(cause);
                        closeResourcesAfterStartFailure(cause);
                        notifyFailure(snapshotLocked(), cause);
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
                transactionRuntime.close();
            } catch (RuntimeException exception) {
                failure = exception;
            }
            try {
                transportAssembly.close();
            } catch (RuntimeException exception) {
                if (failure == null) {
                    failure = exception;
                } else {
                    failure.addSuppressed(exception);
                }
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
                notifyState(snapshotLocked());
            } else {
                state = SipStackState.FAILED;
                lastFailure = summary(failure);
                closed.completeExceptionally(failure);
                notifyFailure(snapshotLocked(), failure);
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
            transactionRuntime.close();
        } catch (RuntimeException closeFailure) {
            startFailure.addSuppressed(closeFailure);
        }
        try {
            resources.close();
        } catch (RuntimeException closeFailure) {
            startFailure.addSuppressed(closeFailure);
        }
    }

    private StackStateSnapshot snapshotLocked() {
        return transactionRuntime.snapshot(state, transportAssembly.snapshot(), java.util.Optional.ofNullable(lastFailure));
    }

    private void notifyState(StackStateSnapshot snapshot) {
        notifyListener(() -> listener.onStateChanged(snapshot));
    }

    private void notifyFailure(StackStateSnapshot snapshot, Throwable cause) {
        notifyListener(() -> listener.onFailure(snapshot, cause));
    }

    private void notifyListener(Runnable callback) {
        try { resources.callbackExecutor().execute(() -> { try { callback.run(); } catch (Throwable ignored) { } }); }
        catch (RuntimeException ignored) { }
    }

    private static String summary(Throwable failure) {
        String message = failure.getMessage();
        return failure.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    }
}
