package org.loomsip.subscription;

/** Cause recorded when a Subscription reaches its terminal state. */
public enum SubscriptionTerminationReason {
    /** Remote NOTIFY reported Subscription-State: terminated. */
    REMOTE_TERMINATED,
    /** Local Expires: 0 cancellation completed. */
    LOCAL_CANCELLED,
    /** Subscription Expires timer elapsed. */
    EXPIRED,
    /** UAS setup failed before a successful SUBSCRIBE response was sent. */
    SETUP_FAILED,
    /** Local application completed an event package and sent its final notification. */
    LOCAL_COMPLETED,
    /** Transaction or transport failure made the subscription unusable. */
    TRANSPORT_FAILURE,
    /** Owning SubscriptionManager closed. */
    MANAGER_CLOSED
}
