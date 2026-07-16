package org.loomsip.transaction.event;

import org.loomsip.message.SipResponse;
import org.loomsip.transport.TransportContext;

import java.util.Objects;

/**
 * Inbound SIP response routed to a client transaction.
 *
 * @param response immutable response
 * @param context inbound network metadata
 */
public record ResponseReceived(
        SipResponse response,
        TransportContext context
) implements TransactionEvent {

    /**
     * Validates and creates an inbound response event.
     *
     * @throws NullPointerException if a component is {@code null}
     */
    public ResponseReceived {
        Objects.requireNonNull(response, "response");
        Objects.requireNonNull(context, "context");
    }
}
