package org.loomsip.subscription;

import org.junit.jupiter.api.Test;
import org.loomsip.message.SipBody;
import org.loomsip.message.SipHeaders;
import org.loomsip.message.SipRequest;
import org.loomsip.message.SipUri;
import org.loomsip.message.header.EventHeaderValue;
import org.loomsip.message.header.SentBy;
import org.loomsip.message.header.SubscriptionState;
import org.loomsip.message.header.SubscriptionStateHeaderValue;
import org.loomsip.message.header.ViaTransport;
import org.loomsip.transaction.noninvite.NonInviteTransactionConfig;
import org.loomsip.transaction.noninvite.NonInviteTransactionManager;
import org.loomsip.transaction.timer.SipTimerConfig;
import org.loomsip.transaction.timer.VirtualSipScheduler;
import org.loomsip.transport.SendResult;
import org.loomsip.transport.TransportEndpoint;
import org.loomsip.transport.TransportProtocol;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SubscriptionPublisherTest {

    @Test
    void buildsManagedNotify() throws Exception {
        TransportEndpoint local = TransportEndpoint.udp(new InetSocketAddress(InetAddress.getLoopbackAddress(), 5080));
        TransportEndpoint remote = TransportEndpoint.udp(new InetSocketAddress(InetAddress.getLoopbackAddress(), 5081));
        AtomicReference<SipRequest> sent = new AtomicReference<>();
        VirtualSipScheduler scheduler = new VirtualSipScheduler();
        try (NonInviteTransactionManager transactions = new NonInviteTransactionManager(
                (message, target) -> { sent.set((SipRequest) message); return CompletableFuture.completedFuture(new SendResult(local, target, 1)); },
                SipTimerConfig.DEFAULT, NonInviteTransactionConfig.DEFAULT,
                (transaction, response, context) -> { }, (transaction, request, context) -> { },
                scheduler, Runnable::run, Runnable::run
        )) {
            SubscriptionPublisher publisher = new SubscriptionPublisher(transactions,
                    new SubscriptionRequestProfile(ViaTransport.UDP, new SentBy("server.example.com", 5080), TransportProtocol.UDP, true),
                    () -> "z9hG4bK-notify");
            publisher.publish(new SubscriptionNotification(
                    new SubscriptionId("subscription@example.com", "server-tag", "client-tag", new EventHeaderValue("presence", Optional.empty())),
                    SipUri.parse("sip:alice@example.com"), SipUri.parse("sip:bob@example.com"), SipUri.parse("sip:alice@example.com"),
                    2, new SubscriptionStateHeaderValue(SubscriptionState.ACTIVE, Optional.empty(), Optional.of(120), Optional.empty()),
                    SipHeaders.empty(), SipBody.empty(), remote
            ));
            assertEquals("presence", sent.get().headers().firstValue("Event").orElseThrow());
            assertEquals("active;expires=120", sent.get().headers().firstValue("Subscription-State").orElseThrow());
            assertEquals("2 NOTIFY", sent.get().headers().firstValue("CSeq").orElseThrow());
        } finally { scheduler.close(); }
    }
}
