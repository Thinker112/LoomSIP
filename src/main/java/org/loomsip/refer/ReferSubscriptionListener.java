package org.loomsip.refer;

import org.loomsip.subscription.SubscriptionHandle;
import org.loomsip.transport.TransportContext;

/**
 * Observes creation of a successfully accepted implicit refer Subscription.
 *
 * <pre>{@code
 * accepted REFER + Refer-Sub:true --> active Subscription --> TU notification setup
 * }</pre>
 */
@FunctionalInterface
public interface ReferSubscriptionListener {

    /**
     * Receives the active implicit refer Subscription after the 2xx response is submitted.
     *
     * @param request parsed accepted REFER request
     * @param subscription active UAS refer-subscription handle
     * @param context inbound REFER transport metadata
     */
    void onSubscriptionCreated(ReferRequest request, SubscriptionHandle subscription, TransportContext context);

    /** @return a listener which intentionally performs no notification setup */
    static ReferSubscriptionListener noop() {
        return (request, subscription, context) -> { };
    }
}
