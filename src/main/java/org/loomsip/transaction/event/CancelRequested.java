package org.loomsip.transaction.event;

import org.loomsip.message.SipRequest;
import org.loomsip.transport.TransportContext;

import java.util.Objects;

/**
 * Cross-transaction command reporting a CANCEL related to an INVITE server transaction.
 *
 * @param cancel immutable CANCEL request
 * @param context inbound CANCEL network metadata
 */
public record CancelRequested(
        SipRequest cancel,
        TransportContext context
) implements TransactionEvent {

    /** Validates the cancellation context. */
    public CancelRequested {
        Objects.requireNonNull(cancel, "cancel");
        Objects.requireNonNull(context, "context");
    }
}
