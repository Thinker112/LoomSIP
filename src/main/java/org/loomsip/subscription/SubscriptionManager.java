package org.loomsip.subscription;

import org.loomsip.concurrent.SerialMailbox;
import org.loomsip.message.header.SubscriptionStateHeaderValue;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Capacity-bounded owner of independent mailbox-serialized Subscriptions.
 *
 * <pre>{@code
 * SubscriptionId --> SubscriptionManager --> Subscription Mailbox
 *                                             |       |
 *                                             v       v
 *                                          lifecycle  terminal cleanup
 * }</pre>
 */
public final class SubscriptionManager implements AutoCloseable {

    private final SubscriptionConfig config;
    private final Executor executor;
    private final Consumer<? super Throwable> failureListener;
    private final Map<SubscriptionId, Subscription> subscriptions = new ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean();

    /** Creates a manager with explicit capacity, execution, and failure dependencies. */
    public SubscriptionManager(SubscriptionConfig config, Executor executor, Consumer<? super Throwable> failureListener) {
        this.config = Objects.requireNonNull(config, "config");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.failureListener = Objects.requireNonNull(failureListener, "failureListener");
    }

    /** Creates or returns the pending Subscription for one stable identity. */
    public SubscriptionHandle create(SubscriptionId id) {
        ensureOpen();
        return subscriptions.computeIfAbsent(Objects.requireNonNull(id, "id"), key -> {
            if (subscriptions.size() >= config.subscriptions()) {
                throw new IllegalStateException("Subscription repository capacity reached");
            }
            return new Subscription(key);
        });
    }

    /** Finds one active or pending Subscription by identity. */
    public Optional<SubscriptionHandle> find(SubscriptionId id) {
        return Optional.ofNullable(subscriptions.get(Objects.requireNonNull(id, "id")));
    }

    /** Transitions one pending Subscription to active. */
    public CompletionStage<SubscriptionSnapshot> activate(SubscriptionId id) {
        return require(id).activate();
    }

    /**
     * Applies one already-correlated inbound NOTIFY lifecycle state.
     *
     * <p>Transaction and Dialog correlation remain outside this manager. A
     * terminated NOTIFY performs the same one-way cleanup as other terminal
     * causes; later NOTIFY events cannot recreate the removed Subscription.</p>
     *
     * @param id correlated local subscription identity
     * @param state parsed Subscription-State value from NOTIFY
     * @return resulting immutable Subscription snapshot
     */
    public CompletionStage<SubscriptionSnapshot> onNotify(
            SubscriptionId id,
            SubscriptionStateHeaderValue state
    ) {
        return require(id).onNotify(Objects.requireNonNull(state, "state"));
    }

    /** Terminates one Subscription and removes it after mailbox cleanup. */
    public CompletionStage<Void> terminate(SubscriptionId id, SubscriptionTerminationReason reason) {
        return require(id).terminate(Objects.requireNonNull(reason, "reason"));
    }

    /** @return current manager-owned Subscription count */
    public int size() {
        return subscriptions.size();
    }

    /** Terminates every Subscription and rejects later creation. */
    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            subscriptions.values().forEach(subscription -> subscription.terminate(SubscriptionTerminationReason.MANAGER_CLOSED));
        }
    }

    private Subscription require(SubscriptionId id) {
        Subscription subscription = subscriptions.get(Objects.requireNonNull(id, "id"));
        if (subscription == null) {
            throw new IllegalArgumentException("unknown Subscription: " + id);
        }
        return subscription;
    }

    private void ensureOpen() {
        if (closed.get()) {
            throw new IllegalStateException("SubscriptionManager is closed");
        }
    }

    private final class Subscription implements SubscriptionHandle {
        private final SubscriptionId id;
        private final SerialMailbox<Event> mailbox;
        private final CompletableFuture<Void> terminated = new CompletableFuture<>();
        private volatile SubscriptionSnapshot snapshot;

        private Subscription(SubscriptionId id) {
            this.id = id;
            snapshot = new SubscriptionSnapshot(id, SubscriptionLifecycleState.PENDING, Optional.empty());
            mailbox = new SerialMailbox<>(executor, this::handle, SubscriptionManager.this::reportFailure, config.mailboxCapacity());
        }

        @Override public SubscriptionId id() { return id; }
        @Override public SubscriptionSnapshot snapshot() { return snapshot; }
        @Override public CompletionStage<Void> terminated() { return terminated.minimalCompletionStage(); }

        private CompletionStage<SubscriptionSnapshot> activate() {
            CompletableFuture<SubscriptionSnapshot> result = new CompletableFuture<>();
            submit(new Activate(result), result);
            return result.minimalCompletionStage();
        }

        private CompletionStage<Void> terminate(SubscriptionTerminationReason reason) {
            CompletableFuture<Void> result = new CompletableFuture<>();
            submit(new Terminate(reason, result), result);
            return result.minimalCompletionStage();
        }

        private CompletionStage<SubscriptionSnapshot> onNotify(SubscriptionStateHeaderValue state) {
            CompletableFuture<SubscriptionSnapshot> result = new CompletableFuture<>();
            submit(new Notify(state, result), result);
            return result.minimalCompletionStage();
        }

        private void handle(Event event) {
            if (event instanceof Activate activate) {
                if (snapshot.state() == SubscriptionLifecycleState.PENDING) {
                    snapshot = new SubscriptionSnapshot(id, SubscriptionLifecycleState.ACTIVE, Optional.empty());
                    activate.result().complete(snapshot);
                } else {
                    activate.result().completeExceptionally(new IllegalStateException("Subscription is not pending"));
                }
            } else if (event instanceof Terminate terminate) {
                if (snapshot.state() != SubscriptionLifecycleState.TERMINATED) {
                    snapshot = new SubscriptionSnapshot(id, SubscriptionLifecycleState.TERMINATED, Optional.of(terminate.reason()));
                    subscriptions.remove(id, this);
                    terminated.complete(null);
                    mailbox.close();
                }
                terminate.result().complete(null);
            } else if (event instanceof Notify notify) {
                if (snapshot.state() == SubscriptionLifecycleState.TERMINATED) {
                    notify.result().complete(snapshot);
                    return;
                }
                switch (notify.state().state()) {
                    case PENDING -> snapshot = new SubscriptionSnapshot(
                            id, SubscriptionLifecycleState.PENDING, Optional.empty()
                    );
                    case ACTIVE -> snapshot = new SubscriptionSnapshot(
                            id, SubscriptionLifecycleState.ACTIVE, Optional.empty()
                    );
                    case TERMINATED -> {
                        snapshot = new SubscriptionSnapshot(
                                id,
                                SubscriptionLifecycleState.TERMINATED,
                                Optional.of(SubscriptionTerminationReason.REMOTE_TERMINATED)
                        );
                        subscriptions.remove(id, this);
                        terminated.complete(null);
                        mailbox.close();
                    }
                }
                notify.result().complete(snapshot);
            }
        }

        private void submit(Event event, CompletableFuture<?> result) {
            try { mailbox.submit(event); } catch (Throwable cause) { result.completeExceptionally(cause); }
        }

        private sealed interface Event permits Activate, Terminate, Notify { }
        private record Activate(CompletableFuture<SubscriptionSnapshot> result) implements Event { }
        private record Terminate(SubscriptionTerminationReason reason, CompletableFuture<Void> result) implements Event { }
        private record Notify(
                SubscriptionStateHeaderValue state,
                CompletableFuture<SubscriptionSnapshot> result
        ) implements Event { }
    }

    private void reportFailure(Throwable cause) {
        try { failureListener.accept(cause); } catch (Throwable ignored) { }
    }
}
