package org.loomsip.subscription;

/**
 * Observes the single terminal transition of a mailbox-serialized Subscription.
 *
 * <pre>{@code
 * Subscription Mailbox --> terminal Snapshot --> SubscriptionTerminationListener
 * }</pre>
 *
 * <p>Implementations must return promptly and must not attempt to revive the
 * Subscription through its manager. The callback is invoked at most once for
 * each Subscription instance, after its identity has been removed.</p>
 */
@FunctionalInterface
public interface SubscriptionTerminationListener {

    /**
     * Receives one immutable terminal Subscription snapshot.
     *
     * @param snapshot terminal lifecycle state after identity removal
     */
    void onTerminated(SubscriptionSnapshot snapshot);

    /** @return a listener which intentionally ignores terminal transitions */
    static SubscriptionTerminationListener noop() {
        return snapshot -> { };
    }
}
