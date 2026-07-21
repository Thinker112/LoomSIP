package org.loomsip.refer;

import org.junit.jupiter.api.Test;
import org.loomsip.message.SipHeaders;
import org.loomsip.message.SipMethod;
import org.loomsip.message.SipRequest;
import org.loomsip.message.SipResponse;
import org.loomsip.message.SipUri;
import org.loomsip.subscription.SubscriptionConfig;
import org.loomsip.subscription.SubscriptionLifecycleState;
import org.loomsip.subscription.SubscriptionManager;
import org.loomsip.transaction.TransactionKey;
import org.loomsip.transaction.noninvite.NonInviteServerState;
import org.loomsip.transaction.noninvite.ServerTransactionHandle;
import org.loomsip.transport.TransportContext;
import org.loomsip.transport.TransportProtocol;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ReferServerListenerTest {

    @Test
    void acceptsReferCreatesActiveImplicitSubscriptionAndNotifiesTu() throws Exception {
        SubscriptionManager manager = manager();
        AtomicReference<org.loomsip.subscription.SubscriptionHandle> observed = new AtomicReference<>();
        ReferServerListener listener = listener(manager, (request, handle, context) -> observed.set(handle));
        RecordingTransaction transaction = new RecordingTransaction();

        listener.onRequest(transaction, refer(true), context());

        assertEquals(202, transaction.response.get().statusCode());
        assertEquals(1, manager.size());
        assertEquals(SubscriptionLifecycleState.ACTIVE, observed.get().snapshot().state());
        assertEquals("refer", observed.get().id().event().packageName());
        assertEquals("server-tag", observed.get().id().localTag());
    }

    @Test
    void referSubFalseAcceptsWithoutCreatingImplicitSubscription() throws Exception {
        SubscriptionManager manager = manager();
        ReferServerListener listener = listener(manager, ReferSubscriptionListener.noop());
        RecordingTransaction transaction = new RecordingTransaction();

        listener.onRequest(transaction, refer(false), context());

        assertEquals(202, transaction.response.get().statusCode());
        assertEquals(0, manager.size());
    }

    @Test
    void malformedReferReturnsBadRequestBeforeHandler() throws Exception {
        SubscriptionManager manager = manager();
        AtomicInteger calls = new AtomicInteger();
        ReferServerListener listener = new ReferServerListener(request -> {
            calls.incrementAndGet();
            return CompletableFuture.completedFuture(new ReferAcceptance(202, "Accepted"));
        }, manager, (transaction, request, context) -> { }, Runnable::run, failure -> { throw new AssertionError(failure); });
        RecordingTransaction transaction = new RecordingTransaction();
        SipRequest malformed = new SipRequest(SipMethod.REFER, SipUri.parse("sip:bob@example.com"), commonHeaders().build());

        listener.onRequest(transaction, malformed, context());

        assertEquals(400, transaction.response.get().statusCode());
        assertEquals(0, calls.get());
    }

    @Test
    void outOfDialogReferUsesGeneratedTagForResponseAndImplicitSubscription() throws Exception {
        SubscriptionManager manager = manager();
        AtomicReference<org.loomsip.subscription.SubscriptionHandle> observed = new AtomicReference<>();
        ReferServerListener listener = new ReferServerListener(
                request -> CompletableFuture.completedFuture(new ReferAcceptance(202, "Accepted")),
                manager, (request, handle, context) -> observed.set(handle), (transaction, request, context) -> { },
                Runnable::run, failure -> { throw new AssertionError(failure); }, () -> "generated-tag"
        );
        RecordingTransaction transaction = new RecordingTransaction();
        SipRequest request = new SipRequest(SipMethod.REFER, SipUri.parse("sip:bob@example.com"), commonHeadersWithoutToTag()
                .add("Refer-To", "<sip:carol@example.com>").build());

        listener.onRequest(transaction, request, context());

        assertEquals(202, transaction.response.get().statusCode());
        assertEquals("generated-tag", transaction.response.get().headers().firstValue("To").orElseThrow().split("tag=")[1]);
        assertEquals("generated-tag", observed.get().id().localTag());
    }

    private static ReferServerListener listener(SubscriptionManager manager, ReferSubscriptionListener observer) {
        return new ReferServerListener(request -> CompletableFuture.completedFuture(new ReferAcceptance(202, "Accepted")), manager,
                observer, (transaction, request, context) -> { }, Runnable::run, failure -> { throw new AssertionError(failure); });
    }

    private static SubscriptionManager manager() {
        return new SubscriptionManager(SubscriptionConfig.DEFAULT, Runnable::run, failure -> { throw new AssertionError(failure); });
    }

    private static SipRequest refer(boolean subscribe) {
        SipHeaders.Builder headers = commonHeaders().add("Refer-To", "<sip:carol@example.com>");
        if (!subscribe) {
            headers.add("Refer-Sub", "false");
        }
        return new SipRequest(SipMethod.REFER, SipUri.parse("sip:bob@example.com"), headers.build());
    }

    private static SipHeaders.Builder commonHeaders() {
        return SipHeaders.builder()
                .add("Via", "SIP/2.0/UDP client.example.com;branch=z9hG4bK-refer-server")
                .add("From", "<sip:alice@example.com>;tag=client-tag")
                .add("To", "<sip:bob@example.com>;tag=server-tag")
                .add("Call-ID", "refer@example.com")
                .add("CSeq", "2 REFER");
    }

    private static SipHeaders.Builder commonHeadersWithoutToTag() {
        return SipHeaders.builder()
                .add("Via", "SIP/2.0/UDP client.example.com;branch=z9hG4bK-refer-server")
                .add("From", "<sip:alice@example.com>;tag=client-tag")
                .add("To", "<sip:bob@example.com>")
                .add("Call-ID", "refer@example.com")
                .add("CSeq", "2 REFER");
    }

    private static TransportContext context() throws Exception {
        return new TransportContext(TransportProtocol.UDP, new InetSocketAddress(InetAddress.getLoopbackAddress(), 5060),
                new InetSocketAddress(InetAddress.getLoopbackAddress(), 5061));
    }

    private static final class RecordingTransaction implements ServerTransactionHandle {
        private final AtomicReference<SipResponse> response = new AtomicReference<>();
        @Override public TransactionKey key() { throw new UnsupportedOperationException(); }
        @Override public NonInviteServerState state() { return NonInviteServerState.TRYING; }
        @Override public void sendResponse(SipResponse value) { response.set(value); }
        @Override public java.util.concurrent.CompletionStage<Void> terminated() { return CompletableFuture.completedFuture(null); }
    }
}
