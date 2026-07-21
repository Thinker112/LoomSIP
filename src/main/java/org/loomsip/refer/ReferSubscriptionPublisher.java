package org.loomsip.refer;

import org.loomsip.concurrent.SerialMailbox;
import org.loomsip.message.SipBody;
import org.loomsip.message.header.SubscriptionState;
import org.loomsip.message.header.SubscriptionStateHeaderValue;
import org.loomsip.subscription.SubscriptionHandle;
import org.loomsip.subscription.SubscriptionId;
import org.loomsip.subscription.SubscriptionManager;
import org.loomsip.subscription.SubscriptionNotification;
import org.loomsip.subscription.SubscriptionTerminationReason;
import org.loomsip.transaction.noninvite.ClientTransactionHandle;

import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Serializes one implicit refer Subscription's NOTIFY progress and final cleanup.
 *
 * <pre>{@code
 * TU transfer progress --> Refer Publisher Mailbox --> ReferNotifier --> NICT
 *                                       |
 *                                       +--> final sipfrag --> Subscription Mailbox terminate
 * }
 * </pre>
 *
 * <p>This coordinator owns publication CSeq and the one-final-NOTIFY invariant.
 * It does not mutate Subscription state directly: the existing
 * {@link SubscriptionManager} remains the sole owner of lifecycle cleanup.</p>
 */
public final class ReferSubscriptionPublisher implements AutoCloseable {

    private final SubscriptionManager subscriptions;
    private final SubscriptionHandle subscription;
    private final ReferNotifier notifier;
    private final ReferSubscriptionProfile profile;
    private final SerialMailbox<Event> mailbox;
    private final Consumer<? super Throwable> failureListener;
    private final Set<CompletableFuture<ClientTransactionHandle>> pending = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean closed = new AtomicBoolean();
    private long nextCseq;
    private boolean finalStarted;

    /**
     * Creates a publisher with an explicit bounded mailbox for one active refer Subscription.
     *
     * @param subscriptions owner of the referenced Subscription lifecycle
     * @param subscription active implicit refer Subscription handle
     * @param notifier refer-specific NOTIFY construction boundary
     * @param profile immutable UAS routing and first CSeq data
     * @param executor executor used only while publication events are queued
     * @param failureListener receives unexpected callback or publication failures
     * @param mailboxCapacity maximum queued progress events
     */
    public ReferSubscriptionPublisher(
            SubscriptionManager subscriptions,
            SubscriptionHandle subscription,
            ReferNotifier notifier,
            ReferSubscriptionProfile profile,
            Executor executor,
            Consumer<? super Throwable> failureListener,
            int mailboxCapacity
    ) {
        this.subscriptions = Objects.requireNonNull(subscriptions, "subscriptions");
        this.subscription = Objects.requireNonNull(subscription, "subscription");
        this.notifier = Objects.requireNonNull(notifier, "notifier");
        this.profile = Objects.requireNonNull(profile, "profile");
        this.failureListener = Objects.requireNonNull(failureListener, "failureListener");
        if (subscription.snapshot().state() != org.loomsip.subscription.SubscriptionLifecycleState.ACTIVE) {
            throw new IllegalArgumentException("refer Subscription must be active");
        }
        if (!"refer".equals(subscription.id().event().normalizedPackageName())
                || subscription.id().event().eventId().isPresent()) {
            throw new IllegalArgumentException("refer Subscription must use Event: refer without an event-id");
        }
        nextCseq = profile.initialCseq();
        mailbox = new SerialMailbox<>(Objects.requireNonNull(executor, "executor"), this::handle, this::reportFailure,
                mailboxCapacity);
        subscription.terminated().whenComplete((unused, failure) -> closeFromSubscription(failure));
    }

    /**
     * Starts one ordered progress or final refer NOTIFY.
     *
     * @param status transfer progress as a SIP-fragment status
     * @return started NICT transaction after managed headers and CSeq are assigned
     */
    public CompletionStage<ClientTransactionHandle> publish(SipfragStatus status) {
        Objects.requireNonNull(status, "status");
        CompletableFuture<ClientTransactionHandle> result = new CompletableFuture<>();
        if (closed.get()) {
            result.completeExceptionally(new IllegalStateException("refer Subscription publisher is closed"));
            return result.minimalCompletionStage();
        }
        pending.add(result);
        try {
            mailbox.submit(new Publish(status, result));
        } catch (Throwable cause) {
            pending.remove(result);
            result.completeExceptionally(cause);
        }
        return result.minimalCompletionStage();
    }

    /**
     * Rejects later publication requests without terminating the Subscription.
     *
     * <p>Stack shutdown should terminate the owning SubscriptionManager first;
     * its terminal callback invokes the same closure path.</p>
     */
    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            mailbox.closeNow();
            failPending(new IllegalStateException("refer Subscription publisher is closed"));
        }
    }

    private void handle(Event event) {
        if (event instanceof Publish publish) {
            publish(publish);
        }
    }

    private void publish(Publish event) {
        if (closed.get() || finalStarted) {
            completeFailure(event.result(), new IllegalStateException("refer Subscription has already terminated"));
            return;
        }
        boolean terminal = event.status().isFinal();
        if (terminal) {
            finalStarted = true;
        }
        try {
            ClientTransactionHandle transaction = notifier.publish(notificationFor(event.status()), event.status());
            nextCseq++;
            pending.remove(event.result());
            event.result().complete(transaction);
            if (terminal) {
                subscriptions.terminate(subscription.id(), SubscriptionTerminationReason.LOCAL_COMPLETED)
                        .whenComplete((unused, failure) -> {
                            if (failure != null) {
                                reportFailure(failure);
                            }
                        });
            }
        } catch (Throwable cause) {
            completeFailure(event.result(), cause);
            if (terminal) {
                subscriptions.terminate(subscription.id(), SubscriptionTerminationReason.TRANSPORT_FAILURE)
                        .whenComplete((unused, failure) -> {
                            if (failure != null) {
                                reportFailure(failure);
                            }
                        });
            }
        }
    }

    private SubscriptionNotification notificationFor(SipfragStatus status) {
        SubscriptionStateHeaderValue state = status.isFinal()
                ? new SubscriptionStateHeaderValue(SubscriptionState.TERMINATED, java.util.Optional.of("noresource"),
                java.util.Optional.empty(), java.util.Optional.empty())
                : new SubscriptionStateHeaderValue(SubscriptionState.ACTIVE, java.util.Optional.empty(),
                java.util.Optional.empty(), java.util.Optional.empty());
        SubscriptionId id = subscription.id();
        return new SubscriptionNotification(id, profile.requestUri(), profile.localUri(), profile.remoteUri(), nextCseq,
                state, profile.additionalHeaders(), SipBody.empty(), profile.target());
    }

    private void closeFromSubscription(Throwable failure) {
        if (failure != null) {
            reportFailure(failure);
        }
        close();
    }

    private void completeFailure(CompletableFuture<ClientTransactionHandle> result, Throwable cause) {
        pending.remove(result);
        result.completeExceptionally(cause);
    }

    private void failPending(Throwable cause) {
        pending.forEach(result -> result.completeExceptionally(cause));
        pending.clear();
    }

    private void reportFailure(Throwable cause) {
        try { failureListener.accept(cause); } catch (Throwable ignored) { }
    }

    private sealed interface Event permits Publish { }
    private record Publish(SipfragStatus status, CompletableFuture<ClientTransactionHandle> result) implements Event { }
}
