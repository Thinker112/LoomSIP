package org.loomsip.subscription;

import org.junit.jupiter.api.Test;
import org.loomsip.message.SipHeaders;
import org.loomsip.message.SipMethod;
import org.loomsip.message.SipRequest;
import org.loomsip.message.SipResponse;
import org.loomsip.message.SipUri;
import org.loomsip.message.header.EventHeaderValue;
import org.loomsip.transaction.TransactionKey;
import org.loomsip.transaction.noninvite.NonInviteServerState;
import org.loomsip.transaction.noninvite.ServerTransactionHandle;
import org.loomsip.transport.TransportContext;
import org.loomsip.transport.TransportProtocol;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SubscriptionSubscribeServerListenerTest {

    @Test
    void acceptsSubscribeCreatesPendingSubscriptionAndReturnsExpires() throws Exception {
        SubscriptionManager manager = manager();
        SubscriptionDispatcher dispatcher = new SubscriptionDispatcher();
        dispatcher.register(new EventHeaderValue("presence", Optional.empty()), request -> CompletableFuture.completedFuture(
                new SubscriptionAcceptance(202, "Accepted", 120)
        ));
        SubscriptionSubscribeServerListener listener = listener(dispatcher, manager);
        RecordingTransaction transaction = new RecordingTransaction();

        listener.onRequest(transaction, subscribe("presence"), context());

        assertEquals(202, transaction.response.get().statusCode());
        assertEquals("120", transaction.response.get().headers().firstValue("Expires").orElseThrow());
        assertEquals("server-tag", transaction.response.get().headers().firstValue("To").orElseThrow().split("tag=")[1]);
        assertEquals(1, manager.size());
    }

    @Test
    void rejectsUnknownPackageWithoutCreatingSubscription() throws Exception {
        SubscriptionManager manager = manager();
        SubscriptionSubscribeServerListener listener = listener(new SubscriptionDispatcher(), manager);
        RecordingTransaction transaction = new RecordingTransaction();

        listener.onRequest(transaction, subscribe("refer"), context());

        assertEquals(489, transaction.response.get().statusCode());
        assertEquals(0, manager.size());
    }

    private static SubscriptionSubscribeServerListener listener(SubscriptionDispatcher dispatcher, SubscriptionManager manager) {
        return new SubscriptionSubscribeServerListener(dispatcher, manager,
                (transaction, request, context) -> { }, () -> "server-tag", Runnable::run,
                failure -> { throw new AssertionError(failure); });
    }
    private static SubscriptionManager manager() { return new SubscriptionManager(SubscriptionConfig.DEFAULT, Runnable::run, failure -> { throw new AssertionError(failure); }); }
    private static SipRequest subscribe(String event) { return new SipRequest(SipMethod.SUBSCRIBE, SipUri.parse("sip:bob@example.com"), SipHeaders.builder()
            .add("Via", "SIP/2.0/UDP client.example.com;branch=z9hG4bK-uas-subscribe")
            .add("From", "<sip:alice@example.com>;tag=client-tag").add("To", "<sip:bob@example.com>")
            .add("Call-ID", "uas-subscription@example.com").add("CSeq", "1 SUBSCRIBE")
            .add("Event", event).add("Expires", "3600").build()); }
    private static TransportContext context() throws Exception { return new TransportContext(TransportProtocol.UDP,
            new InetSocketAddress(InetAddress.getLoopbackAddress(), 5060), new InetSocketAddress(InetAddress.getLoopbackAddress(), 5061)); }
    private static final class RecordingTransaction implements ServerTransactionHandle {
        private final AtomicReference<SipResponse> response = new AtomicReference<>();
        @Override public TransactionKey key() { throw new UnsupportedOperationException(); }
        @Override public NonInviteServerState state() { return NonInviteServerState.TRYING; }
        @Override public void sendResponse(SipResponse value) { response.set(value); }
        @Override public java.util.concurrent.CompletionStage<Void> terminated() { return CompletableFuture.completedFuture(null); }
    }
}
