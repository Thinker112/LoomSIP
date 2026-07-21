package org.loomsip.subscription;

import org.junit.jupiter.api.Test;
import org.loomsip.message.SipHeaders;
import org.loomsip.message.SipMethod;
import org.loomsip.message.SipRequest;
import org.loomsip.message.SipResponse;
import org.loomsip.message.SipUri;
import org.loomsip.message.header.EventHeaderValue;
import org.loomsip.transaction.TransactionKey;
import org.loomsip.transaction.noninvite.NonInviteServerListener;
import org.loomsip.transaction.noninvite.NonInviteServerState;
import org.loomsip.transaction.noninvite.ServerTransactionHandle;
import org.loomsip.transport.TransportContext;
import org.loomsip.transport.TransportProtocol;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SubscriptionNotifyServerListenerTest {

    @Test
    void routesNotifyAndDelegatesOtherMethods() throws Exception {
        SubscriptionManager manager = new SubscriptionManager(SubscriptionConfig.DEFAULT, Runnable::run, failure -> {
            throw new AssertionError(failure);
        });
        manager.create(id());
        AtomicInteger delegated = new AtomicInteger();
        SubscriptionNotifyServerListener listener = new SubscriptionNotifyServerListener(
                new SubscriptionNotifyRouter(manager),
                (transaction, request, context) -> delegated.incrementAndGet(),
                Runnable::run,
                failure -> {
                    throw new AssertionError(failure);
                }
        );
        RecordingTransaction notify = new RecordingTransaction();

        listener.onRequest(notify, request(SipMethod.NOTIFY, true), context());

        assertEquals(200, notify.response.get().statusCode());
        assertEquals(0, delegated.get());

        listener.onRequest(new RecordingTransaction(), request(SipMethod.OPTIONS, false), context());
        assertEquals(1, delegated.get());
    }

    private static SubscriptionId id() {
        return new SubscriptionId("subscription@example.com", "local-tag", "remote-tag",
                new EventHeaderValue("presence", Optional.empty()));
    }

    private static SipRequest request(SipMethod method, boolean event) {
        SipHeaders.Builder headers = SipHeaders.builder()
                .add("Via", "SIP/2.0/UDP notifier.example.com;branch=z9hG4bK-listener")
                .add("From", "<sip:bob@example.com>;tag=remote-tag")
                .add("To", "<sip:alice@example.com>;tag=local-tag")
                .add("Call-ID", "subscription@example.com")
                .add("CSeq", "1 " + method.value());
        if (event) {
            headers.add("Event", "presence").add("Subscription-State", "active");
        }
        return new SipRequest(method, SipUri.parse("sip:alice@example.com"), headers.build());
    }

    private static TransportContext context() throws Exception {
        return new TransportContext(
                TransportProtocol.UDP,
                new InetSocketAddress(InetAddress.getLoopbackAddress(), 5060),
                new InetSocketAddress(InetAddress.getLoopbackAddress(), 5061)
        );
    }

    private static final class RecordingTransaction implements ServerTransactionHandle {
        private final AtomicReference<SipResponse> response = new AtomicReference<>();
        @Override public TransactionKey key() { throw new UnsupportedOperationException(); }
        @Override public NonInviteServerState state() { return NonInviteServerState.PROCEEDING; }
        @Override public void sendResponse(SipResponse value) { response.set(value); }
        @Override public java.util.concurrent.CompletionStage<Void> terminated() { return CompletableFuture.completedFuture(null); }
    }
}
