package org.loomsip.refer;

import org.loomsip.message.SipHeaders;
import org.loomsip.message.SipUri;
import org.loomsip.transport.TransportEndpoint;

import java.util.Objects;

/**
 * UAS-owned route and CSeq baseline for one implicit refer Subscription.
 *
 * <pre>{@code
 * accepted REFER --> ReferSubscriptionProfile --> ReferSubscriptionPublisher
 * }</pre>
 *
 * @param requestUri resolved remote Contact used as the NOTIFY request URI
 * @param localUri local identity rendered in NOTIFY From
 * @param remoteUri referring identity rendered in NOTIFY To
 * @param initialCseq first UAS NOTIFY CSeq; later publications increment it
 * @param additionalHeaders application headers excluding managed Content-Type
 * @param target resolved transport target
 */
public record ReferSubscriptionProfile(
        SipUri requestUri,
        SipUri localUri,
        SipUri remoteUri,
        long initialCseq,
        SipHeaders additionalHeaders,
        TransportEndpoint target
) {

    /** Validates immutable refer NOTIFY route and initial sequence data. */
    public ReferSubscriptionProfile {
        Objects.requireNonNull(requestUri, "requestUri");
        Objects.requireNonNull(localUri, "localUri");
        Objects.requireNonNull(remoteUri, "remoteUri");
        if (initialCseq < 0 || initialCseq > 0x7fff_ffffL) {
            throw new IllegalArgumentException("initialCseq must be a valid SIP sequence number");
        }
        Objects.requireNonNull(additionalHeaders, "additionalHeaders");
        Objects.requireNonNull(target, "target");
    }
}
