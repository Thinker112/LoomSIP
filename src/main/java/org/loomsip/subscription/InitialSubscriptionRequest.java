package org.loomsip.subscription;

import org.loomsip.message.SipBody;
import org.loomsip.message.SipHeaders;
import org.loomsip.message.SipUri;
import org.loomsip.message.header.EventHeaderValue;
import org.loomsip.message.header.ExpiresHeaderValue;
import org.loomsip.transport.TransportEndpoint;

import java.util.Objects;

/** Caller-owned immutable parameters for one initial out-of-dialog SUBSCRIBE. */
public record InitialSubscriptionRequest(
        SipUri requestUri,
        SipUri localUri,
        SipUri remoteUri,
        String callId,
        String localTag,
        long cseq,
        EventHeaderValue event,
        ExpiresHeaderValue expires,
        SipHeaders additionalHeaders,
        SipBody body,
        TransportEndpoint target
) {
    /** Validates immutable subscription request parameters. */
    public InitialSubscriptionRequest {
        Objects.requireNonNull(requestUri, "requestUri");
        Objects.requireNonNull(localUri, "localUri");
        Objects.requireNonNull(remoteUri, "remoteUri");
        callId = requireText(callId, "callId");
        localTag = requireText(localTag, "localTag");
        if (cseq < 0 || cseq > 0x7fff_ffffL) {
            throw new IllegalArgumentException("cseq must be a valid SIP sequence number");
        }
        Objects.requireNonNull(event, "event");
        Objects.requireNonNull(expires, "expires");
        Objects.requireNonNull(additionalHeaders, "additionalHeaders");
        Objects.requireNonNull(body, "body");
        Objects.requireNonNull(target, "target");
    }

    private static String requireText(String value, String name) {
        value = Objects.requireNonNull(value, name);
        if (value.isBlank() || value.chars().anyMatch(Character::isWhitespace)) {
            throw new IllegalArgumentException(name + " must not be blank or contain whitespace");
        }
        return value;
    }
}
