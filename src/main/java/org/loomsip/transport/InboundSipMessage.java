package org.loomsip.transport;

import org.loomsip.message.SipMessage;

import java.util.Objects;

/**
 * Immutable SIP message paired with the network metadata needed by higher layers.
 *
 * @param message parsed SIP request or response
 * @param context inbound network metadata
 */
public record InboundSipMessage(
        SipMessage message,
        TransportContext context
) {

    /**
     * Validates and creates an inbound message event.
     *
     * @throws NullPointerException if a component is {@code null}
     */
    public InboundSipMessage {
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(context, "context");
    }
}
