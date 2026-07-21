package org.loomsip.message.header;

import java.util.Objects;
import java.util.Optional;

/** Typed RFC 3265 Subscription-State value and supported parameters. */
public record SubscriptionStateHeaderValue(
        SubscriptionState state,
        Optional<String> reason,
        Optional<Integer> expires,
        Optional<Integer> retryAfter
) {

    /** Validates state-dependent reason and non-negative intervals. */
    public SubscriptionStateHeaderValue {
        state = Objects.requireNonNull(state, "state");
        reason = Objects.requireNonNull(reason, "reason").map(value ->
                HeaderSyntax.requireToken(value, "Subscription-State reason")
        );
        expires = requireInterval(expires, "expires");
        retryAfter = requireInterval(retryAfter, "retry-after");
        if (reason.isPresent() && state != SubscriptionState.TERMINATED) {
            throw new IllegalArgumentException("Subscription-State reason requires terminated state");
        }
    }

    /** @return canonical Subscription-State wire value */
    public String wireValue() {
        StringBuilder value = new StringBuilder(state.wireValue());
        reason.ifPresent(item -> value.append(";reason=").append(item));
        expires.ifPresent(item -> value.append(";expires=").append(item));
        retryAfter.ifPresent(item -> value.append(";retry-after=").append(item));
        return value.toString();
    }

    private static Optional<Integer> requireInterval(Optional<Integer> value, String name) {
        value = Objects.requireNonNull(value, name);
        if (value.isPresent() && value.orElseThrow() < 0) {
            throw new IllegalArgumentException(name + " must not be negative");
        }
        return value;
    }
}
