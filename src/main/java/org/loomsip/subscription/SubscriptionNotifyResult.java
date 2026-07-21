package org.loomsip.subscription;

import org.loomsip.message.SipResponse;

import java.util.Objects;
import java.util.Optional;

/** Result of validating and routing one inbound NOTIFY before its NIST response is sent. */
public record SubscriptionNotifyResult(SipResponse response, Optional<SubscriptionSnapshot> subscription) {

    /** Validates the SIP response and optional matched Subscription snapshot. */
    public SubscriptionNotifyResult {
        Objects.requireNonNull(response, "response");
        subscription = Objects.requireNonNull(subscription, "subscription");
    }
}
