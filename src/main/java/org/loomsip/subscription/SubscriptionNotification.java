package org.loomsip.subscription;

import org.loomsip.message.SipBody;
import org.loomsip.message.SipHeaders;
import org.loomsip.message.SipUri;
import org.loomsip.message.header.SubscriptionStateHeaderValue;
import org.loomsip.transport.TransportEndpoint;

import java.util.Objects;

/** Caller-owned immutable payload and routing data for one UAS NOTIFY publication. */
public record SubscriptionNotification(
        SubscriptionId id,
        SipUri requestUri,
        SipUri localUri,
        SipUri remoteUri,
        long cseq,
        SubscriptionStateHeaderValue state,
        SipHeaders additionalHeaders,
        SipBody body,
        TransportEndpoint target
) {
    /** Validates immutable publication parameters. */
    public SubscriptionNotification {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(requestUri, "requestUri");
        Objects.requireNonNull(localUri, "localUri");
        Objects.requireNonNull(remoteUri, "remoteUri");
        if (cseq < 0 || cseq > 0x7fff_ffffL) {
            throw new IllegalArgumentException("cseq must be a valid SIP sequence number");
        }
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(additionalHeaders, "additionalHeaders");
        Objects.requireNonNull(body, "body");
        Objects.requireNonNull(target, "target");
    }
}
