package org.loomsip.subscription;

import java.util.Objects;
import java.util.Optional;

/** Immutable externally visible Subscription state. */
public record SubscriptionSnapshot(
        SubscriptionId id,
        SubscriptionLifecycleState state,
        Optional<SubscriptionTerminationReason> terminationReason
) {

    /** Validates terminal-state consistency. */
    public SubscriptionSnapshot {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(state, "state");
        terminationReason = Objects.requireNonNull(terminationReason, "terminationReason");
        if ((state == SubscriptionLifecycleState.TERMINATED) != terminationReason.isPresent()) {
            throw new IllegalArgumentException("termination reason must exist only for terminated Subscription");
        }
    }
}
