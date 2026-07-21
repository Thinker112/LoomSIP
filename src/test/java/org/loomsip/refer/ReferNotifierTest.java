package org.loomsip.refer;

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
import org.loomsip.subscription.SubscriptionId;
import org.loomsip.subscription.SubscriptionNotification;
import org.loomsip.subscription.SubscriptionPublisher;
import org.loomsip.subscription.SubscriptionRequestProfile;
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
import static org.junit.jupiter.api.Assertions.assertThrows;

class ReferNotifierTest {

    @Test
    void publishesReferSipfragWithManagedContentType() throws Exception {
        TransportEndpoint local = TransportEndpoint.udp(new InetSocketAddress(InetAddress.getLoopbackAddress(), 5080));
        TransportEndpoint remote = TransportEndpoint.udp(new InetSocketAddress(InetAddress.getLoopbackAddress(), 5081));
        AtomicReference<SipRequest> sent = new AtomicReference<>();
        VirtualSipScheduler scheduler = new VirtualSipScheduler();
        try (NonInviteTransactionManager transactions = new NonInviteTransactionManager(
                (message, target) -> {
                    sent.set((SipRequest) message);
                    return CompletableFuture.completedFuture(new SendResult(local, target, 1));
                }, SipTimerConfig.DEFAULT, NonInviteTransactionConfig.DEFAULT,
                (transaction, response, context) -> { }, (transaction, request, context) -> { },
                scheduler, Runnable::run, Runnable::run
        )) {
            ReferNotifier notifier = new ReferNotifier(new SubscriptionPublisher(transactions,
                    new SubscriptionRequestProfile(ViaTransport.UDP, new SentBy("server.example.com", 5080),
                            TransportProtocol.UDP, true), () -> "z9hG4bK-refer"));

            notifier.publish(notification(remote, SubscriptionState.ACTIVE), new SipfragStatus(180, "Ringing"));

            assertEquals("refer", sent.get().headers().firstValue("Event").orElseThrow());
            assertEquals("message/sipfrag", sent.get().headers().firstValue("Content-Type").orElseThrow());
            assertEquals(new SipfragStatus(180, "Ringing"), SipfragStatus.parse(sent.get().body()));
        } finally {
            scheduler.close();
        }
    }

    @Test
    void rejectsMismatchedTerminalStatus() throws Exception {
        TransportEndpoint remote = TransportEndpoint.udp(new InetSocketAddress(InetAddress.getLoopbackAddress(), 5081));
        VirtualSipScheduler scheduler = new VirtualSipScheduler();
        try (NonInviteTransactionManager transactions = new NonInviteTransactionManager(
                (message, target) -> CompletableFuture.completedFuture(new SendResult(remote, target, 1)),
                SipTimerConfig.DEFAULT, NonInviteTransactionConfig.DEFAULT,
                (transaction, response, context) -> { }, (transaction, request, context) -> { },
                scheduler, Runnable::run, Runnable::run
        )) {
            ReferNotifier notifier = new ReferNotifier(new SubscriptionPublisher(transactions,
                    new SubscriptionRequestProfile(ViaTransport.UDP, new SentBy("server.example.com", 5080),
                            TransportProtocol.UDP, true), () -> "z9hG4bK-refer"));

            assertThrows(IllegalArgumentException.class, () -> notifier.publish(
                    notification(remote, SubscriptionState.ACTIVE), new SipfragStatus(200, "OK")
            ));
        } finally {
            scheduler.close();
        }
    }

    private static SubscriptionNotification notification(TransportEndpoint target, SubscriptionState state) throws Exception {
        return new SubscriptionNotification(
                new SubscriptionId("refer@example.com", "server-tag", "client-tag",
                        new EventHeaderValue("refer", Optional.empty())),
                SipUri.parse("sip:client@example.com"), SipUri.parse("sip:server@example.com"),
                SipUri.parse("sip:client@example.com"), 2,
                new SubscriptionStateHeaderValue(state, Optional.empty(), Optional.empty(), Optional.empty()),
                SipHeaders.empty(), SipBody.empty(), target
        );
    }
}
