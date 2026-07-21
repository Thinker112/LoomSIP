package org.loomsip.refer;

import org.loomsip.message.SipMethod;
import org.loomsip.message.SipRequest;
import org.loomsip.message.SipResponses;
import org.loomsip.subscription.SubscriptionHandle;
import org.loomsip.subscription.SubscriptionId;
import org.loomsip.subscription.SubscriptionManager;
import org.loomsip.subscription.SubscriptionTerminationReason;
import org.loomsip.transaction.noninvite.NonInviteServerListener;
import org.loomsip.transaction.noninvite.ServerTransactionHandle;
import org.loomsip.transport.TransportContext;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * NIST adapter for RFC 3515 REFER dispatch and implicit refer-subscription setup.
 *
 * <pre>{@code
 * REFER --> ReferRequest --> ReferHandler --> 2xx response
 *                                      |              |
 *                                      v              v
 *                           Refer-Sub:true      active Subscription --> TU listener
 * }</pre>
 *
 * <p>The listener does not execute a transfer or construct NOTIFY routing.
 * Those application-owned responsibilities receive an active handle through
 * {@link ReferSubscriptionListener} after the response is submitted.</p>
 */
public final class ReferServerListener implements NonInviteServerListener {

    private final ReferHandler handler;
    private final SubscriptionManager subscriptions;
    private final ReferSubscriptionListener subscriptionListener;
    private final NonInviteServerListener downstream;
    private final Executor callbackExecutor;
    private final Consumer<? super Throwable> failureListener;
    private final Supplier<String> tagGenerator;

    /** Creates a REFER adapter without an implicit-subscription observer. */
    public ReferServerListener(
            ReferHandler handler,
            SubscriptionManager subscriptions,
            NonInviteServerListener downstream,
            Executor callbackExecutor,
            Consumer<? super Throwable> failureListener
    ) {
        this(handler, subscriptions, ReferSubscriptionListener.noop(), downstream, callbackExecutor, failureListener, null);
    }

    /** Creates a REFER adapter with all protocol and TU callback dependencies. */
    public ReferServerListener(
            ReferHandler handler,
            SubscriptionManager subscriptions,
            ReferSubscriptionListener subscriptionListener,
            NonInviteServerListener downstream,
            Executor callbackExecutor,
            Consumer<? super Throwable> failureListener
    ) {
        this(handler, subscriptions, subscriptionListener, downstream, callbackExecutor, failureListener, null);
    }

    /**
     * Creates a REFER adapter that can also accept out-of-dialog REFER requests.
     *
     * <p>The tag generator is used only when an accepted REFER has no incoming
     * To tag. In-dialog REFER continues to reuse the existing Dialog tag.</p>
     */
    public ReferServerListener(
            ReferHandler handler,
            SubscriptionManager subscriptions,
            ReferSubscriptionListener subscriptionListener,
            NonInviteServerListener downstream,
            Executor callbackExecutor,
            Consumer<? super Throwable> failureListener,
            Supplier<String> tagGenerator
    ) {
        this.handler = Objects.requireNonNull(handler, "handler");
        this.subscriptions = Objects.requireNonNull(subscriptions, "subscriptions");
        this.subscriptionListener = Objects.requireNonNull(subscriptionListener, "subscriptionListener");
        this.downstream = Objects.requireNonNull(downstream, "downstream");
        this.callbackExecutor = Objects.requireNonNull(callbackExecutor, "callbackExecutor");
        this.failureListener = Objects.requireNonNull(failureListener, "failureListener");
        this.tagGenerator = tagGenerator;
    }

    /** Parses REFER and delegates all other Non-INVITE requests unchanged. */
    @Override
    public void onRequest(ServerTransactionHandle transaction, SipRequest request, TransportContext context) {
        if (!SipMethod.REFER.equals(request.method())) {
            downstream.onRequest(transaction, request, context);
            return;
        }
        try {
            ReferRequest refer = ReferRequest.parse(request);
            handler.onRefer(refer).whenCompleteAsync(
                    (acceptance, failure) -> complete(transaction, refer, context, acceptance, failure), callbackExecutor
            );
        } catch (Throwable cause) {
            report(cause);
            send(transaction, request, 400, "Bad Request");
        }
    }

    @Override public void onTransportFailure(ServerTransactionHandle transaction, Throwable cause) { downstream.onTransportFailure(transaction, cause); }
    @Override public void onTerminated(ServerTransactionHandle transaction) { downstream.onTerminated(transaction); }
    @Override public void onLayerError(Throwable cause) { downstream.onLayerError(cause); }

    private void complete(
            ServerTransactionHandle transaction,
            ReferRequest refer,
            TransportContext context,
            ReferAcceptance acceptance,
            Throwable failure
    ) {
        if (failure != null || acceptance == null) {
            report(failure == null ? new IllegalStateException("REFER handler returned no acceptance") : failure);
            send(transaction, refer.request(), 500, "Server Internal Error");
            return;
        }
        String localTag;
        try {
            localTag = acceptance.accepted() ? localTag(refer.request()) : null;
        } catch (Throwable cause) {
            report(cause);
            send(transaction, refer.request(), 500, "Server Internal Error", null);
            return;
        }
        if (!acceptance.accepted() || !refer.referSub().enabled()) {
            send(transaction, refer.request(), acceptance.statusCode(), acceptance.reasonPhrase(), localTag);
            return;
        }
        try {
            SubscriptionId id = SubscriptionId.fromIncomingRefer(refer.request(), localTag);
            SubscriptionHandle handle = subscriptions.create(id);
            subscriptions.activate(id).whenCompleteAsync((snapshot, activationFailure) -> {
                if (activationFailure != null) {
                    report(activationFailure);
                    subscriptions.terminate(id, SubscriptionTerminationReason.SETUP_FAILED);
                    send(transaction, refer.request(), 500, "Server Internal Error", localTag);
                    return;
                }
                send(transaction, refer.request(), acceptance.statusCode(), acceptance.reasonPhrase(), localTag);
                try {
                    subscriptionListener.onSubscriptionCreated(refer, handle, context);
                } catch (Throwable cause) {
                    report(cause);
                }
            }, callbackExecutor);
        } catch (Throwable cause) {
            report(cause);
            send(transaction, refer.request(), 500, "Server Internal Error", localTag);
        }
    }

    private void send(ServerTransactionHandle transaction, SipRequest request, int statusCode, String reasonPhrase) {
        send(transaction, request, statusCode, reasonPhrase, null);
    }

    private void send(
            ServerTransactionHandle transaction,
            SipRequest request,
            int statusCode,
            String reasonPhrase,
            String localTag
    ) {
        try {
            transaction.sendResponse(localTag == null
                    ? SipResponses.createResponse(request, statusCode, reasonPhrase)
                    : SipResponses.createResponse(request, statusCode, reasonPhrase, localTag));
        }
        catch (Throwable cause) { report(cause); }
    }

    private String localTag(SipRequest request) throws org.loomsip.message.header.SipHeaderValueException {
        Optional<String> existing = org.loomsip.message.header.SipHeaderValues.toTag(request.headers());
        if (existing.isPresent()) {
            return existing.orElseThrow();
        }
        if (tagGenerator == null) {
            throw new IllegalStateException("out-of-dialog REFER requires a local tag generator");
        }
        String generated = Objects.requireNonNull(tagGenerator.get(), "tagGenerator result");
        if (generated.isBlank()) {
            throw new IllegalArgumentException("tag generator returned blank tag");
        }
        return generated;
    }

    private void report(Throwable cause) {
        try { failureListener.accept(cause); } catch (Throwable ignored) { }
    }
}
