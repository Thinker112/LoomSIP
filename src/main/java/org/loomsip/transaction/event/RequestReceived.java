package org.loomsip.transaction.event;

import org.loomsip.message.SipRequest;
import org.loomsip.transport.TransportContext;

import java.util.Objects;

/**
 * Inbound SIP request routed to a server transaction.
 *
 * @param request immutable request
 * @param context inbound network metadata
 */
public record RequestReceived(
        SipRequest request,
        TransportContext context
) implements TransactionEvent {

    /**
     * Validates and creates an inbound request event.
     *
     * @throws NullPointerException if a component is {@code null}
     */
    public RequestReceived {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(context, "context");
    }
}
