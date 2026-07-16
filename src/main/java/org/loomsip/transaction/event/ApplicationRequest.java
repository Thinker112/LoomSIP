package org.loomsip.transaction.event;

import org.loomsip.message.SipRequest;
import org.loomsip.transport.TransportEndpoint;

import java.util.Objects;

/**
 * TU command to start sending a request through a client transaction.
 *
 * @param request immutable request
 * @param target selected remote transport endpoint
 */
public record ApplicationRequest(
        SipRequest request,
        TransportEndpoint target
) implements TransactionEvent {

    /**
     * Validates and creates an application request command.
     *
     * @throws NullPointerException if a component is {@code null}
     */
    public ApplicationRequest {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(target, "target");
    }
}
