package org.loomsip.subscription;

import org.loomsip.message.SipMethod;
import org.loomsip.message.SipRequest;
import org.loomsip.message.SipResponses;
import org.loomsip.message.header.SipHeaderValues;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Validates and routes one inbound NOTIFY to an existing Subscription Mailbox.
 *
 * <pre>{@code
 * NIST initial NOTIFY
 *         |
 *         v
 * SubscriptionNotifyRouter --> SubscriptionManager --> Subscription Mailbox
 *         |
 *         v
 * 200 / 400 / 481 response
 * }</pre>
 */
public final class SubscriptionNotifyRouter {

    private final SubscriptionManager subscriptions;

    /** Creates a router over one local Subscription manager. */
    public SubscriptionNotifyRouter(SubscriptionManager subscriptions) {
        this.subscriptions = Objects.requireNonNull(subscriptions, "subscriptions");
    }

    /**
     * Routes one NOTIFY and selects the response the caller must send on its NIST.
     *
     * @param request immutable inbound NOTIFY request
     * @return asynchronous route result after the target Subscription Mailbox processes state
     */
    public CompletionStage<SubscriptionNotifyResult> route(SipRequest request) {
        Objects.requireNonNull(request, "request");
        if (!SipMethod.NOTIFY.equals(request.method())) {
            return CompletableFuture.completedFuture(result(request, 400, "Bad Request", Optional.empty()));
        }
        try {
            SubscriptionId id = SubscriptionId.fromIncomingNotify(request.headers());
            if (subscriptions.find(id).isEmpty()) {
                return CompletableFuture.completedFuture(result(
                        request, 481, "Call/Transaction Does Not Exist", Optional.empty()
                ));
            }
            return subscriptions.onNotify(id, SipHeaderValues.subscriptionState(request.headers()))
                    .thenApply(snapshot -> result(request, 200, "OK", Optional.of(snapshot)));
        } catch (Exception exception) {
            return CompletableFuture.completedFuture(result(request, 400, "Bad Request", Optional.empty()));
        }
    }

    private static SubscriptionNotifyResult result(
            SipRequest request,
            int status,
            String reason,
            Optional<SubscriptionSnapshot> snapshot
    ) {
        return new SubscriptionNotifyResult(SipResponses.createResponse(request, status, reason), snapshot);
    }
}
