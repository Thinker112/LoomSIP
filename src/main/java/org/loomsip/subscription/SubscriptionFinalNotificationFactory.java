package org.loomsip.subscription;

import org.loomsip.message.SipRequest;
import org.loomsip.transport.TransportContext;

/**
 * Derives UAS-owned final-NOTIFY routing data when an initial SUBSCRIBE is accepted.
 *
 * <pre>{@code
 * accepted SUBSCRIBE + transport context --> final notification context
 * }</pre>
 *
 * <p>The factory is intentionally supplied by the stack integration point:
 * remote Contact routing and the next NOTIFY CSeq are protocol context not
 * owned by the generic Subscription state machine.</p>
 */
@FunctionalInterface
public interface SubscriptionFinalNotificationFactory {

    /**
     * Creates final-NOTIFY context for one accepted UAS Subscription.
     *
     * @param request accepted initial SUBSCRIBE request
     * @param context inbound transport metadata for the request
     * @param id UAS Subscription identity with generated local tag
     * @return immutable final-NOTIFY routing and payload context
     */
    SubscriptionFinalNotification create(SipRequest request, TransportContext context, SubscriptionId id);
}
