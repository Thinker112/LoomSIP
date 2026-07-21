package org.loomsip.subscription;

import org.junit.jupiter.api.Test;
import org.loomsip.message.SipHeaders;
import org.loomsip.message.SipMethod;
import org.loomsip.message.SipRequest;
import org.loomsip.message.SipResponse;
import org.loomsip.message.SipResponses;
import org.loomsip.message.SipUri;
import org.loomsip.transaction.TransactionKey;
import org.loomsip.transaction.noninvite.ClientTransactionHandle;
import org.loomsip.transaction.noninvite.NonInviteClientListener;
import org.loomsip.transaction.noninvite.NonInviteClientState;
import org.loomsip.transport.TransportContext;
import org.loomsip.transport.TransportProtocol;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SubscriptionSubscribeClientListenerTest {

    @Test
    void createsSubscriptionBeforeForwardingSuccessfulSubscribeResponse() throws Exception {
        SubscriptionManager manager = new SubscriptionManager(SubscriptionConfig.DEFAULT, Runnable::run, failure -> {
            throw new AssertionError(failure);
        });
        AtomicInteger callbacks = new AtomicInteger();
        SubscriptionSubscribeClientListener listener = new SubscriptionSubscribeClientListener(
                new SubscriptionSubscribeResponseRouter(manager),
                new NonInviteClientListener() {
                    @Override
                    public void onResponse(ClientTransactionHandle transaction, SipResponse response, TransportContext context) {
                        assertEquals(1, manager.size());
                        callbacks.incrementAndGet();
                    }
                },
                Runnable::run,
                failure -> {
                    throw new AssertionError(failure);
                }
        );
        SipRequest request = subscribe();

        listener.onResponse(new Handle(request), SipResponses.createResponse(request, 200, "OK", "remote-tag"), context());

        assertEquals(1, callbacks.get());
        assertEquals(1, manager.size());
    }

    private static SipRequest subscribe() {
        return new SipRequest(SipMethod.SUBSCRIBE, SipUri.parse("sip:bob@example.com"), SipHeaders.builder()
                .add("Via", "SIP/2.0/UDP client.example.com;branch=z9hG4bK-subscribe-listener")
                .add("From", "<sip:alice@example.com>;tag=local-tag")
                .add("To", "<sip:bob@example.com>")
                .add("Call-ID", "subscription@example.com")
                .add("CSeq", "1 SUBSCRIBE")
                .add("Event", "presence")
                .add("Expires", "3600")
                .build());
    }

    private static TransportContext context() throws Exception {
        return new TransportContext(
                TransportProtocol.UDP,
                new InetSocketAddress(InetAddress.getLoopbackAddress(), 5060),
                new InetSocketAddress(InetAddress.getLoopbackAddress(), 5061)
        );
    }

    private record Handle(SipRequest originalRequest) implements ClientTransactionHandle {
        @Override public TransactionKey key() { throw new UnsupportedOperationException(); }
        @Override public NonInviteClientState state() { return NonInviteClientState.TRYING; }
        @Override public java.util.concurrent.CompletionStage<Void> terminated() { return CompletableFuture.completedFuture(null); }
    }
}
