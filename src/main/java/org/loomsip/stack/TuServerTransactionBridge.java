package org.loomsip.stack;

import org.loomsip.message.SipRequest;
import org.loomsip.transaction.invite.InviteServerHandle;
import org.loomsip.transaction.invite.InviteServerListener;
import org.loomsip.transaction.noninvite.NonInviteServerListener;
import org.loomsip.transaction.noninvite.ServerTransactionHandle;
import org.loomsip.transport.TransportContext;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * Adapts existing server Transaction callbacks to the Stack TU handler registry.
 *
 * <pre>{@code
 * IST / NIST callback --> TuServerTransactionBridge --> TuHandlerRegistry
 * }</pre>
 *
 * <p>The Transaction managers invoke this bridge through their ordered TU
 * callback dispatcher. The bridge therefore never executes on a Netty EventLoop.</p>
 */
public final class TuServerTransactionBridge implements InviteServerListener, NonInviteServerListener {

    private final TuHandlerRegistry handlers;
    private final Consumer<Throwable> failureReporter;

    /**
     * Creates a bridge with isolated handler failure reporting.
     *
     * @param handlers frozen application handler registry
     * @param failureReporter non-blocking application failure observer
     */
    public TuServerTransactionBridge(TuHandlerRegistry handlers, Consumer<Throwable> failureReporter) {
        this.handlers = Objects.requireNonNull(handlers, "handlers");
        this.failureReporter = Objects.requireNonNull(failureReporter, "failureReporter");
    }

    @Override
    public void onInvite(InviteServerHandle transaction, SipRequest request, TransportContext context) {
        dispatch(request, context, Objects.requireNonNull(transaction, "transaction")::sendResponse);
    }

    @Override
    public void onRequest(ServerTransactionHandle transaction, SipRequest request, TransportContext context) {
        dispatch(request, context, Objects.requireNonNull(transaction, "transaction")::sendResponse);
    }

    /** Reports an underlying Transaction-layer error without changing its state. */
    @Override
    public void onLayerError(Throwable cause) {
        failureReporter.accept(cause);
    }

    private void dispatch(SipRequest request, TransportContext context, Consumer<org.loomsip.message.SipResponse> responder) {
        handlers.dispatch(new IncomingRequestContext(request, context, responder), failureReporter);
    }
}
