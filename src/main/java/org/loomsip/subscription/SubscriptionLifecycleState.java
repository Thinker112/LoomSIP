package org.loomsip.subscription;

/** Lifecycle state owned exclusively by one Subscription Mailbox. */
public enum SubscriptionLifecycleState {
    /** Local request is pending acceptance or initial NOTIFY. */
    PENDING,
    /** Subscription accepts refreshes and NOTIFY events. */
    ACTIVE,
    /** Subscription is permanently closed and cannot be revived. */
    TERMINATED
}
