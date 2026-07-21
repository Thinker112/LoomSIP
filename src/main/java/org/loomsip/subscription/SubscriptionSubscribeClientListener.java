package org.loomsip.subscription;

import org.loomsip.message.SipMethod;
import org.loomsip.message.SipResponse;
import org.loomsip.transaction.noninvite.ClientTransactionHandle;
import org.loomsip.transaction.noninvite.NonInviteClientListener;
import org.loomsip.transaction.timer.SipTimer;
import org.loomsip.transport.TransportContext;

import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/** NICT listener adapter that creates a pending Subscription after a successful initial SUBSCRIBE. */
public final class SubscriptionSubscribeClientListener implements NonInviteClientListener {

    private final SubscriptionSubscribeResponseRouter router;
    private final NonInviteClientListener downstream;
    private final Executor callbackExecutor;
    private final Consumer<? super Throwable> failureListener;

    /** Creates a SUBSCRIBE response adapter with explicit execution and failure dependencies. */
    public SubscriptionSubscribeClientListener(
            SubscriptionSubscribeResponseRouter router,
            NonInviteClientListener downstream,
            Executor callbackExecutor,
            Consumer<? super Throwable> failureListener
    ) {
        this.router = Objects.requireNonNull(router, "router");
        this.downstream = Objects.requireNonNull(downstream, "downstream");
        this.callbackExecutor = Objects.requireNonNull(callbackExecutor, "callbackExecutor");
        this.failureListener = Objects.requireNonNull(failureListener, "failureListener");
    }

    /** Associates final SUBSCRIBE responses before forwarding the TU callback. */
    @Override
    public void onResponse(ClientTransactionHandle transaction, SipResponse response, TransportContext context) {
        if (!SipMethod.SUBSCRIBE.equals(transaction.originalRequest().method()) || response.statusCode() < 200) {
            downstream.onResponse(transaction, response, context);
            return;
        }
        router.route(transaction.originalRequest(), response).whenCompleteAsync((ignored, failure) -> {
            if (failure != null) {
                report(failure);
            }
            downstream.onResponse(transaction, response, context);
        }, callbackExecutor);
    }

    @Override public void onTimeout(ClientTransactionHandle transaction, SipTimer timer) { downstream.onTimeout(transaction, timer); }
    @Override public void onTransportFailure(ClientTransactionHandle transaction, Throwable cause) { downstream.onTransportFailure(transaction, cause); }
    @Override public void onTerminated(ClientTransactionHandle transaction) { downstream.onTerminated(transaction); }
    @Override public void onLayerError(Throwable cause) { downstream.onLayerError(cause); }

    private void report(Throwable cause) {
        try { failureListener.accept(cause); } catch (Throwable ignored) { }
    }
}
