package org.loomsip.subscription;

import org.loomsip.message.SipBody;
import org.loomsip.message.SipHeaders;
import org.loomsip.message.SipUri;
import org.loomsip.message.header.ExpiresHeaderValue;
import org.loomsip.transport.TransportEndpoint;

import java.util.Objects;

/**
 * Caller-owned parameters for one in-dialog refresh or cancellation SUBSCRIBE.
 *
 * <pre>{@code
 * active Subscription + new Expires --> refresh SUBSCRIBE --> response lifecycle update
 * }</pre>
 *
 * @param id stable UAC Subscription identity containing both dialog tags
 * @param requestUri current remote target used by the refresh request
 * @param localUri local identity rendered in From
 * @param remoteUri remote identity rendered in To
 * @param cseq next UAC SUBSCRIBE CSeq
 * @param expires requested replacement interval, or zero to cancel
 * @param additionalHeaders caller-owned headers excluding managed subscription headers
 * @param body optional refresh body
 * @param target resolved transport destination
 */
public record SubscriptionRefreshRequest(
        SubscriptionId id,
        SipUri requestUri,
        SipUri localUri,
        SipUri remoteUri,
        long cseq,
        ExpiresHeaderValue expires,
        SipHeaders additionalHeaders,
        SipBody body,
        TransportEndpoint target
) {

    /** Validates immutable in-dialog refresh parameters. */
    public SubscriptionRefreshRequest {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(requestUri, "requestUri");
        Objects.requireNonNull(localUri, "localUri");
        Objects.requireNonNull(remoteUri, "remoteUri");
        if (cseq < 0 || cseq > 0x7fff_ffffL) {
            throw new IllegalArgumentException("cseq must be a valid SIP sequence number");
        }
        Objects.requireNonNull(expires, "expires");
        Objects.requireNonNull(additionalHeaders, "additionalHeaders");
        Objects.requireNonNull(body, "body");
        Objects.requireNonNull(target, "target");
    }
}
