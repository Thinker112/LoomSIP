package org.loomsip.subscription;

import org.loomsip.message.SipMethod;
import org.loomsip.message.SipRequest;
import org.loomsip.message.SipResponses;
import org.loomsip.transaction.noninvite.NonInviteServerListener;
import org.loomsip.transaction.noninvite.ServerTransactionHandle;
import org.loomsip.transport.TransportContext;

import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * NIST listener adapter that routes NOTIFY before generic Non-INVITE handling.
 *
 * <pre>{@code
 * NIST callback --> SubscriptionNotifyServerListener --> NOTIFY Router
 *                         |                                 |
 *                         |                                 v
 *                         +--> other methods --> downstream  Subscription Mailbox
 *                                                           |
 *                                                           v
 *                                                      NIST response
 * }</pre>
 */
public final class SubscriptionNotifyServerListener implements NonInviteServerListener {

    private final SubscriptionNotifyRouter router;
    private final NonInviteServerListener downstream;
    private final Executor callbackExecutor;
    private final Consumer<? super Throwable> failureListener;

    /** Creates an adapter with explicit asynchronous callback and failure dependencies. */
    public SubscriptionNotifyServerListener(
            SubscriptionNotifyRouter router,
            NonInviteServerListener downstream,
            Executor callbackExecutor,
            Consumer<? super Throwable> failureListener
    ) {
        this.router = Objects.requireNonNull(router, "router");
        this.downstream = Objects.requireNonNull(downstream, "downstream");
        this.callbackExecutor = Objects.requireNonNull(callbackExecutor, "callbackExecutor");
        this.failureListener = Objects.requireNonNull(failureListener, "failureListener");
    }

    /** Routes NOTIFY or delegates every other method unchanged. */
    @Override
    public void onRequest(ServerTransactionHandle transaction, SipRequest request, TransportContext context) {
        if (!SipMethod.NOTIFY.equals(request.method())) {
            downstream.onRequest(transaction, request, context);
            return;
        }
        router.route(request).whenCompleteAsync((result, failure) -> {
            if (failure != null) {
                send(transaction, SipResponses.createResponse(request, 500, "Server Internal Error"));
                report(failure);
            } else {
                send(transaction, result.response());
            }
        }, callbackExecutor);
    }

    @Override public void onTransportFailure(ServerTransactionHandle transaction, Throwable cause) {
        downstream.onTransportFailure(transaction, cause);
    }
    @Override public void onTerminated(ServerTransactionHandle transaction) { downstream.onTerminated(transaction); }
    @Override public void onLayerError(Throwable cause) { downstream.onLayerError(cause); }

    private void send(ServerTransactionHandle transaction, org.loomsip.message.SipResponse response) {
        try { transaction.sendResponse(response); } catch (Throwable cause) { report(cause); }
    }

    private void report(Throwable cause) {
        try { failureListener.accept(cause); } catch (Throwable ignored) { }
    }
}
