package org.loomsip.subscription;

import org.loomsip.message.SipRequest;
import org.loomsip.message.SipResponse;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** Associates successful initial SUBSCRIBE responses with local pending Subscriptions. */
public final class SubscriptionSubscribeResponseRouter {

    private final SubscriptionManager subscriptions;

    /** Creates a router over one local Subscription manager. */
    public SubscriptionSubscribeResponseRouter(SubscriptionManager subscriptions) {
        this.subscriptions = Objects.requireNonNull(subscriptions, "subscriptions");
    }

    /**
     * Creates the pending UAC Subscription for a successful initial SUBSCRIBE response.
     *
     * @param request original SUBSCRIBE request
     * @param response correlated client transaction response
     * @return empty for provisional or non-2xx responses, otherwise the pending handle
     */
    public CompletionStage<Optional<SubscriptionHandle>> route(SipRequest request, SipResponse response) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(response, "response");
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        try {
            return CompletableFuture.completedFuture(Optional.of(
                    subscriptions.create(SubscriptionId.fromSubscribeResponse(request, response))
            ));
        } catch (Exception exception) {
            return CompletableFuture.failedFuture(exception);
        }
    }
}
