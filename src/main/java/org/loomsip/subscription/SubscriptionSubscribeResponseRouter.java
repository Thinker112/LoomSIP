package org.loomsip.subscription;

import org.loomsip.message.SipRequest;
import org.loomsip.message.SipResponse;
import org.loomsip.message.header.SipHeaderValues;

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
            if (SipHeaderValues.toTag(request.headers()).isPresent()) {
                SubscriptionId id = SubscriptionId.fromSubscribeResponse(request, response);
                SubscriptionHandle handle = subscriptions.find(id).orElseThrow(
                        () -> new IllegalArgumentException("unknown Subscription refresh: " + id)
                );
                return subscriptions.refresh(id, SipHeaderValues.expires(response.headers()))
                        .thenApply(snapshot -> Optional.of(handle));
            }
            return CompletableFuture.completedFuture(Optional.of(
                    subscriptions.create(SubscriptionId.fromSubscribeResponse(request, response))
            ));
        } catch (Exception exception) {
            return CompletableFuture.failedFuture(exception);
        }
    }
}
