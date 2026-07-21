package org.loomsip.subscription;

import org.loomsip.message.header.SubscriptionState;
import org.loomsip.message.header.SubscriptionStateHeaderValue;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Publishes one UAS final NOTIFY for a locally cancelled or expired Subscription.
 *
 * <pre>{@code
 * Subscription Mailbox --> terminal Snapshot --> FinalNotifier --> NICT NOTIFY
 *                                         ^
 *                              registered UAS routing context
 * }</pre>
 *
 * <p>Registration belongs to the UAS integration point, because only it owns
 * Contact routing and the NOTIFY CSeq. The manager only supplies a terminal
 * Snapshot. Removing a registration with a compare-and-remove operation makes
 * Timer, cancel, remote termination, and close races converge on at most one
 * publication.</p>
 */
public final class SubscriptionFinalNotifier implements SubscriptionTerminationListener, AutoCloseable {

    private final SubscriptionPublisher publisher;
    private final Consumer<? super Throwable> failureListener;
    private final Map<SubscriptionId, SubscriptionFinalNotification> notifications = new ConcurrentHashMap<>();

    /** Creates a final notifier using the supplied UAS publisher. */
    public SubscriptionFinalNotifier(SubscriptionPublisher publisher, Consumer<? super Throwable> failureListener) {
        this.publisher = Objects.requireNonNull(publisher, "publisher");
        this.failureListener = Objects.requireNonNull(failureListener, "failureListener");
    }

    /**
     * Registers final-NOTIFY context for one UAS Subscription.
     *
     * <p>The caller must register the exact handle returned by the manager's
     * create operation. A Subscription already terminal at registration is
     * processed immediately, preventing a create/register race from losing its
     * final notification.</p>
     */
    public void register(SubscriptionHandle handle, SubscriptionFinalNotification notification) {
        Objects.requireNonNull(handle, "handle");
        Objects.requireNonNull(notification, "notification");
        if (!handle.id().equals(notification.id())) {
            throw new IllegalArgumentException("final notification identity must match Subscription handle");
        }
        SubscriptionFinalNotification existing = notifications.putIfAbsent(handle.id(), notification);
        if (existing != null && existing != notification) {
            throw new IllegalStateException("final notification is already registered for " + handle.id());
        }
        SubscriptionSnapshot snapshot = handle.snapshot();
        if (snapshot.state() == SubscriptionLifecycleState.TERMINATED) {
            onTerminated(snapshot);
        }
    }

    /** Processes the manager's terminal callback, publishing only local cancel and expiry. */
    @Override
    public void onTerminated(SubscriptionSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        SubscriptionFinalNotification notification = notifications.remove(snapshot.id());
        if (notification == null) {
            return;
        }
        terminalState(snapshot.terminationReason()).ifPresent(state -> publish(notification, state));
    }

    /** Drops every remaining context without publishing during stack shutdown. */
    @Override
    public void close() {
        notifications.clear();
    }

    private void publish(SubscriptionFinalNotification notification, SubscriptionStateHeaderValue state) {
        try {
            publisher.publish(new SubscriptionNotification(
                    notification.id(), notification.requestUri(), notification.localUri(), notification.remoteUri(),
                    notification.cseq(), state, notification.additionalHeaders(), notification.body(), notification.target()
            ));
        } catch (Throwable cause) {
            try { failureListener.accept(cause); } catch (Throwable ignored) { }
        }
    }

    private static Optional<SubscriptionStateHeaderValue> terminalState(
            Optional<SubscriptionTerminationReason> reason
    ) {
        return reason.flatMap(value -> switch (value) {
            case LOCAL_CANCELLED -> Optional.of(new SubscriptionStateHeaderValue(
                    SubscriptionState.TERMINATED, Optional.of("deactivated"), Optional.empty(), Optional.empty()
            ));
            case EXPIRED -> Optional.of(new SubscriptionStateHeaderValue(
                    SubscriptionState.TERMINATED, Optional.of("timeout"), Optional.empty(), Optional.empty()
            ));
            case REMOTE_TERMINATED, SETUP_FAILED, TRANSPORT_FAILURE, MANAGER_CLOSED -> Optional.empty();
        });
    }
}
