package org.loomsip.subscription;

import org.loomsip.message.SipMethod;
import org.loomsip.message.SipRequest;
import org.loomsip.message.SipResponse;
import org.loomsip.message.SipResponses;
import org.loomsip.message.header.ExpiresHeaderValue;
import org.loomsip.message.header.SipHeaderValues;
import org.loomsip.message.header.SipHeaderValueException;
import org.loomsip.transaction.noninvite.NonInviteServerListener;
import org.loomsip.transaction.noninvite.ServerTransactionHandle;
import org.loomsip.transport.TransportContext;

import java.util.Objects;
import java.util.Optional;
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
    private final SubscriptionFinalNotifier finalNotifier;
    private final SubscriptionFinalNotificationFactory finalNotificationFactory;

    /** Creates a UAS SUBSCRIBE adapter with explicit protocol and callback dependencies. */
    public SubscriptionSubscribeServerListener(
            SubscriptionDispatcher dispatcher,
            SubscriptionManager subscriptions,
            NonInviteServerListener downstream,
            Supplier<String> tagGenerator,
            Executor callbackExecutor,
            Consumer<? super Throwable> failureListener
    ) {
        this(dispatcher, subscriptions, downstream, tagGenerator, callbackExecutor, failureListener, null, null);
    }

    /**
     * Creates a UAS SUBSCRIBE adapter with optional final-NOTIFY registration.
     *
     * <p>Both final-NOTIFY arguments must be supplied together. The factory
     * receives the accepted initial request and records the route and CSeq
     * owned by the UAS integration; refreshes retain that registration.</p>
     */
    public SubscriptionSubscribeServerListener(
            SubscriptionDispatcher dispatcher,
            SubscriptionManager subscriptions,
            NonInviteServerListener downstream,
            Supplier<String> tagGenerator,
            Executor callbackExecutor,
            Consumer<? super Throwable> failureListener,
            SubscriptionFinalNotifier finalNotifier,
            SubscriptionFinalNotificationFactory finalNotificationFactory
    ) {
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
        this.subscriptions = Objects.requireNonNull(subscriptions, "subscriptions");
        this.downstream = Objects.requireNonNull(downstream, "downstream");
        this.tagGenerator = Objects.requireNonNull(tagGenerator, "tagGenerator");
        this.callbackExecutor = Objects.requireNonNull(callbackExecutor, "callbackExecutor");
        this.failureListener = Objects.requireNonNull(failureListener, "failureListener");
        if ((finalNotifier == null) != (finalNotificationFactory == null)) {
            throw new IllegalArgumentException("final notifier and notification factory must be configured together");
        }
        this.finalNotifier = finalNotifier;
        this.finalNotificationFactory = finalNotificationFactory;
    }

    /** Dispatches SUBSCRIBE or delegates non-subscription methods unchanged. */
    @Override
    public void onRequest(ServerTransactionHandle transaction, SipRequest request, TransportContext context) {
        if (!SipMethod.SUBSCRIBE.equals(request.method())) {
            downstream.onRequest(transaction, request, context);
            return;
        }
        try {
            Optional<SubscriptionId> refresh = refreshIdentity(request);
            if (refresh.isPresent()) {
                refresh(transaction, request, refresh.orElseThrow());
                return;
            }
            var dispatch = dispatcher.dispatch(request);
            if (dispatch.isEmpty()) {
                send(transaction, SipResponses.createResponse(request, 489, "Bad Event", nextTag()));
                return;
            }
            dispatch.orElseThrow().handler().onSubscribe(dispatch.orElseThrow().request()).whenCompleteAsync(
                    (acceptance, failure) -> complete(transaction, request, context, acceptance, failure), callbackExecutor
            );
        } catch (Throwable cause) {
            send(transaction, SipResponses.createResponse(request, 400, "Bad Request", nextTag()));
        }
    }

    @Override public void onTransportFailure(ServerTransactionHandle transaction, Throwable cause) { downstream.onTransportFailure(transaction, cause); }
    @Override public void onTerminated(ServerTransactionHandle transaction) { downstream.onTerminated(transaction); }
    @Override public void onLayerError(Throwable cause) { downstream.onLayerError(cause); }

    private void complete(
            ServerTransactionHandle transaction,
            SipRequest request,
            TransportContext context,
            SubscriptionAcceptance acceptance,
            Throwable failure
    ) {
        if (failure != null || acceptance == null) {
            report(failure == null ? new IllegalStateException("Subscription handler returned no acceptance") : failure);
            send(transaction, SipResponses.createResponse(request, 500, "Server Internal Error", nextTag()));
            return;
        }
        String tag = nextTag();
        try {
            if (acceptance.accepted()) {
                SubscriptionId id = SubscriptionId.fromIncomingSubscribe(request, tag);
                SubscriptionHandle handle = subscriptions.create(id);
                if (finalNotifier != null) {
                    finalNotifier.register(handle, finalNotificationFactory.create(request, context, id));
                }
                subscriptions.refresh(id, new ExpiresHeaderValue(acceptance.expiresSeconds())).whenCompleteAsync(
                        (snapshot, refreshFailure) -> completeAccepted(transaction, request, acceptance, id, tag, refreshFailure),
                        callbackExecutor
                );
                return;
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

    private void completeAccepted(
            ServerTransactionHandle transaction,
            SipRequest request,
            SubscriptionAcceptance acceptance,
            SubscriptionId id,
            String tag,
            Throwable refreshFailure
    ) {
        if (refreshFailure != null) {
            report(refreshFailure);
            subscriptions.terminate(id, SubscriptionTerminationReason.SETUP_FAILED);
            send(transaction, SipResponses.createResponse(request, 500, "Server Internal Error", tag));
            return;
        }
        SipResponse base = SipResponses.createResponse(request, acceptance.statusCode(), acceptance.reasonPhrase(), tag);
        SipResponse response = new SipResponse(base.version(), base.statusCode(), base.reasonPhrase(),
                base.headers().withReplaced("Expires", Integer.toString(acceptance.expiresSeconds())), base.body());
        send(transaction, response);
    }

    private Optional<SubscriptionId> refreshIdentity(SipRequest request) throws SipHeaderValueException {
        Optional<String> localTag = SipHeaderValues.toTag(request.headers());
        return localTag.map(tag -> {
            try {
                return SubscriptionId.fromIncomingSubscribe(request, tag);
            } catch (SipHeaderValueException exception) {
                throw new IllegalArgumentException(exception);
            }
        });
    }

    private void refresh(ServerTransactionHandle transaction, SipRequest request, SubscriptionId id) {
        try {
            if (subscriptions.find(id).isEmpty()) {
                send(transaction, SipResponses.createResponse(request, 481, "Call/Transaction Does Not Exist", id.localTag()));
                return;
            }
            ExpiresHeaderValue expires = SipHeaderValues.expires(request.headers());
            subscriptions.refresh(id, expires).whenCompleteAsync((snapshot, failure) -> {
                if (failure != null) {
                    report(failure);
                    send(transaction, SipResponses.createResponse(request, 500, "Server Internal Error", id.localTag()));
                    return;
                }
                SipResponse base = SipResponses.createResponse(request, 200, "OK", id.localTag());
                SipResponse response = new SipResponse(base.version(), base.statusCode(), base.reasonPhrase(),
                        base.headers().withReplaced("Expires", Integer.toString(expires.seconds())), base.body());
                send(transaction, response);
            }, callbackExecutor);
        } catch (Throwable cause) {
            report(cause);
            send(transaction, SipResponses.createResponse(request, 400, "Bad Request", id.localTag()));
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
