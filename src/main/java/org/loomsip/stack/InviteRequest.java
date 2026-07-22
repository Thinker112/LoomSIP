package org.loomsip.stack;

import org.loomsip.message.SipMethod;
import org.loomsip.message.SipRequest;
import org.loomsip.transport.TransportEndpoint;

import java.util.Objects;

/** Caller-owned immutable parameters for one initial out-of-dialog INVITE. */
public record InviteRequest(SipRequest request, TransportEndpoint target) {

    /** Validates an INVITE request and its explicitly selected network target. */
    public InviteRequest {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(target, "target");
        if (!SipMethod.INVITE.equals(request.method())) {
            throw new IllegalArgumentException("InviteRequest requires an INVITE request");
        }
    }
}
