package org.loomsip.stack;

import org.loomsip.message.SipRequest;
import org.loomsip.message.SipMethod;
import org.loomsip.transport.TransportEndpoint;

import java.util.Objects;

/** Caller-owned immutable parameters for one out-of-dialog Non-INVITE request. */
public record OutgoingRequest(SipRequest request, TransportEndpoint target) {

    /** Validates the request and its explicitly selected network target. */
    public OutgoingRequest {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(target, "target");
        if (SipMethod.INVITE.equals(request.method()) || SipMethod.ACK.equals(request.method())) {
            throw new IllegalArgumentException("OutgoingRequest cannot use INVITE or ACK");
        }
    }
}
