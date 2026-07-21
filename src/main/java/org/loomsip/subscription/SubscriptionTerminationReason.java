package org.loomsip.subscription;

/** Cause recorded when a Subscription reaches its terminal state. */
public enum SubscriptionTerminationReason {
    /** Remote NOTIFY reported Subscription-State: terminated. */
    REMOTE_TERMINATED,
    /** Local Expires: 0 cancellation completed. */
    LOCAL_CANCELLED,
    /** Subscription Expires timer elapsed. */
    EXPIRED,
    /** Transaction or transport failure made the subscription unusable. */
    TRANSPORT_FAILURE,
    /** Owning SubscriptionManager closed. */
    MANAGER_CLOSED
}
