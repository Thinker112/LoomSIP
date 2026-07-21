package org.loomsip.subscription;

/** Capacity limits for one SubscriptionManager. */
public record SubscriptionConfig(int subscriptions, int mailboxCapacity) {

    /** Conservative initial capacities. */
    public static final SubscriptionConfig DEFAULT = new SubscriptionConfig(10_000, 64);

    /** Validates positive repository and mailbox limits. */
    public SubscriptionConfig {
        if (subscriptions <= 0 || mailboxCapacity <= 0) {
            throw new IllegalArgumentException("subscription capacities must be positive");
        }
    }
}
