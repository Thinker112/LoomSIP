package org.loomsip.subscription;

import org.loomsip.message.SipBody;
import org.loomsip.message.SipHeaders;
import org.loomsip.message.SipUri;
import org.loomsip.transport.TransportEndpoint;

import java.util.Objects;

/**
 * UAS-owned routing and payload data needed to publish one final NOTIFY.
 *
 * <pre>{@code
 * terminal Subscription --> final notification context --> SubscriptionPublisher
 * }</pre>
 *
 * <p>The final Subscription-State is deliberately absent: it is derived from
 * the manager-owned terminal reason, so a caller cannot publish a mismatched
 * reason for an expiry or local cancellation.</p>
 *
 * @param id stable UAS Subscription identity
 * @param requestUri subscriber Contact or other resolved NOTIFY request URI
 * @param localUri UAS identity rendered in From
 * @param remoteUri subscriber identity rendered in To
 * @param cseq next UAS NOTIFY CSeq for this final publication
 * @param additionalHeaders caller-owned non-managed SIP headers
 * @param body final event package body
 * @param target resolved transport destination
 */
public record SubscriptionFinalNotification(
        SubscriptionId id,
        SipUri requestUri,
        SipUri localUri,
        SipUri remoteUri,
        long cseq,
        SipHeaders additionalHeaders,
        SipBody body,
        TransportEndpoint target
) {
    /** Validates final NOTIFY routing and immutable body data. */
    public SubscriptionFinalNotification {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(requestUri, "requestUri");
        Objects.requireNonNull(localUri, "localUri");
        Objects.requireNonNull(remoteUri, "remoteUri");
        if (cseq < 0 || cseq > 0x7fff_ffffL) {
            throw new IllegalArgumentException("cseq must be a valid SIP sequence number");
        }
        Objects.requireNonNull(additionalHeaders, "additionalHeaders");
        Objects.requireNonNull(body, "body");
        Objects.requireNonNull(target, "target");
    }
}
