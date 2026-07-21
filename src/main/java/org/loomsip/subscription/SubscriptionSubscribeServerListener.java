package org.loomsip.subscription;

import org.loomsip.message.SipMethod;
import org.loomsip.message.SipRequest;
import org.loomsip.message.SipResponse;
import org.loomsip.message.SipResponses;
import org.loomsip.transaction.noninvite.NonInviteServerListener;
import org.loomsip.transaction.noninvite.ServerTransactionHandle;
import org.loomsip.transport.TransportContext;

import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** UAS NIST adapter for Event package SUBSCRIBE dispatch and pending Subscription creation. */
public final class SubscriptionSubscribeServerListener implements NonInviteServerListener {

    private final SubscriptionDispatcher dispatcher;
    private final SubscriptionManager subscriptions;
    private final NonInviteServerListener downstream;
    private final Supplier<String> tagGenerator;
    private final Executor callbackExecutor;
    private final Consumer<? super Throwable> failureListener;

    /** Creates a UAS SUBSCRIBE adapter with explicit protocol and callback dependencies. */
    public SubscriptionSubscribeServerListener(
            SubscriptionDispatcher dispatcher,
            SubscriptionManager subscriptions,
            NonInviteServerListener downstream,
            Supplier<String> tagGenerator,
            Executor callbackExecutor,
            Consumer<? super Throwable> failureListener
    ) {
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
        this.subscriptions = Objects.requireNonNull(subscriptions, "subscriptions");
        this.downstream = Objects.requireNonNull(downstream, "downstream");
        this.tagGenerator = Objects.requireNonNull(tagGenerator, "tagGenerator");
        this.callbackExecutor = Objects.requireNonNull(callbackExecutor, "callbackExecutor");
        this.failureListener = Objects.requireNonNull(failureListener, "failureListener");
    }

    /** Dispatches SUBSCRIBE or delegates non-subscription methods unchanged. */
    @Override
    public void onRequest(ServerTransactionHandle transaction, SipRequest request, TransportContext context) {
        if (!SipMethod.SUBSCRIBE.equals(request.method())) {
            downstream.onRequest(transaction, request, context);
            return;
        }
        try {
            var dispatch = dispatcher.dispatch(request);
            if (dispatch.isEmpty()) {
                send(transaction, SipResponses.createResponse(request, 489, "Bad Event", nextTag()));
                return;
            }
            dispatch.orElseThrow().handler().onSubscribe(dispatch.orElseThrow().request()).whenCompleteAsync(
                    (acceptance, failure) -> complete(transaction, request, acceptance, failure), callbackExecutor
            );
        } catch (Throwable cause) {
            send(transaction, SipResponses.createResponse(request, 400, "Bad Request", nextTag()));
        }
    }

    @Override public void onTransportFailure(ServerTransactionHandle transaction, Throwable cause) { downstream.onTransportFailure(transaction, cause); }
    @Override public void onTerminated(ServerTransactionHandle transaction) { downstream.onTerminated(transaction); }
    @Override public void onLayerError(Throwable cause) { downstream.onLayerError(cause); }

    private void complete(ServerTransactionHandle transaction, SipRequest request, SubscriptionAcceptance acceptance, Throwable failure) {
        if (failure != null || acceptance == null) {
            report(failure == null ? new IllegalStateException("Subscription handler returned no acceptance") : failure);
            send(transaction, SipResponses.createResponse(request, 500, "Server Internal Error", nextTag()));
            return;
        }
        String tag = nextTag();
        try {
            if (acceptance.accepted()) {
                subscriptions.create(SubscriptionId.fromIncomingSubscribe(request, tag));
            }
            SipResponse base = SipResponses.createResponse(request, acceptance.statusCode(), acceptance.reasonPhrase(), tag);
            SipResponse response = new SipResponse(base.version(), base.statusCode(), base.reasonPhrase(),
                    base.headers().withReplaced("Expires", Integer.toString(acceptance.expiresSeconds())), base.body());
            send(transaction, response);
        } catch (Throwable cause) {
            report(cause);
            send(transaction, SipResponses.createResponse(request, 500, "Server Internal Error", tag));
        }
    }

    private String nextTag() {
        String tag = Objects.requireNonNull(tagGenerator.get(), "tagGenerator result");
        if (tag.isBlank()) { throw new IllegalArgumentException("tag generator returned blank tag"); }
        return tag;
    }
    private void send(ServerTransactionHandle transaction, SipResponse response) { try { transaction.sendResponse(response); } catch (Throwable cause) { report(cause); } }
    private void report(Throwable cause) { try { failureListener.accept(cause); } catch (Throwable ignored) { } }
}
