package org.loomsip.subscription;

import org.loomsip.message.SipRequest;
import org.loomsip.message.header.EventHeaderValue;
import org.loomsip.message.header.ExpiresHeaderValue;

import java.util.Objects;

/** Parsed immutable SUBSCRIBE event request delivered to one UAS package handler. */
public record SubscriptionEventRequest(SipRequest request, EventHeaderValue event, ExpiresHeaderValue expires) {

    /** Validates parsed request components. */
    public SubscriptionEventRequest {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(event, "event");
        Objects.requireNonNull(expires, "expires");
    }
}
