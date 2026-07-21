package org.loomsip.subscription;

import org.junit.jupiter.api.Test;
import org.loomsip.message.SipBody;
import org.loomsip.message.SipHeaders;
import org.loomsip.message.SipRequest;
import org.loomsip.message.SipUri;
import org.loomsip.message.header.EventHeaderValue;
import org.loomsip.message.header.ExpiresHeaderValue;
import org.loomsip.message.header.SentBy;
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
import static org.junit.jupiter.api.Assertions.assertThrows;

class SubscriptionClientTest {

    @Test
    void buildsAndStartsManagedInitialSubscribe() throws Exception {
        TransportEndpoint local = TransportEndpoint.udp(new InetSocketAddress(InetAddress.getLoopbackAddress(), 5070));
        TransportEndpoint remote = TransportEndpoint.udp(new InetSocketAddress(InetAddress.getLoopbackAddress(), 5071));
        AtomicReference<SipRequest> sent = new AtomicReference<>();
        VirtualSipScheduler scheduler = new VirtualSipScheduler();
        try (NonInviteTransactionManager transactions = new NonInviteTransactionManager(
                (message, target) -> {
                    sent.set((SipRequest) message);
                    return CompletableFuture.completedFuture(new SendResult(local, target, 1));
                },
                SipTimerConfig.DEFAULT,
                NonInviteTransactionConfig.DEFAULT,
                (transaction, response, context) -> { },
                (transaction, request, context) -> { },
                scheduler,
                Runnable::run,
                Runnable::run
        )) {
            SubscriptionClient client = new SubscriptionClient(
                    transactions,
                    new SubscriptionRequestProfile(ViaTransport.UDP, new SentBy("client.example.com", 5070),
                            TransportProtocol.UDP, true),
                    () -> "z9hG4bK-subscription"
            );

            client.subscribe(request(remote));

            assertEquals("SIP/2.0/UDP client.example.com:5070;branch=z9hG4bK-subscription;rport",
                    sent.get().headers().firstValue("Via").orElseThrow());
            assertEquals("presence;id=watcher", sent.get().headers().firstValue("Event").orElseThrow());
            assertEquals("3600", sent.get().headers().firstValue("Expires").orElseThrow());
            assertEquals("1 SUBSCRIBE", sent.get().headers().firstValue("CSeq").orElseThrow());
        } finally {
            scheduler.close();
        }
    }

    @Test
    void rejectsCallerManagedSubscriptionHeaders() throws Exception {
        TransportEndpoint remote = TransportEndpoint.udp(new InetSocketAddress(InetAddress.getLoopbackAddress(), 5071));
        VirtualSipScheduler scheduler = new VirtualSipScheduler();
        try (NonInviteTransactionManager transactions = new NonInviteTransactionManager(
                (message, target) -> CompletableFuture.completedFuture(new SendResult(target, target, 1)),
                SipTimerConfig.DEFAULT, NonInviteTransactionConfig.DEFAULT,
                (transaction, response, context) -> { }, (transaction, request, context) -> { },
                scheduler, Runnable::run, Runnable::run
        )) {
            SubscriptionClient client = new SubscriptionClient(
                    transactions,
                    new SubscriptionRequestProfile(ViaTransport.UDP, new SentBy("client.example.com", 5070),
                            TransportProtocol.UDP, true),
                    () -> "z9hG4bK-subscription"
            );
            InitialSubscriptionRequest request = new InitialSubscriptionRequest(
                    SipUri.parse("sip:bob@example.com"), SipUri.parse("sip:alice@example.com"),
                    SipUri.parse("sip:bob@example.com"), "subscription@example.com", "local-tag", 1,
                    new EventHeaderValue("presence", Optional.empty()), new ExpiresHeaderValue(3600),
                    SipHeaders.builder().add("Event", "override").build(), SipBody.empty(), remote
            );
            assertThrows(IllegalArgumentException.class, () -> client.subscribe(request));
        } finally {
            scheduler.close();
        }
    }

    @Test
    void buildsManagedInDialogRefresh() throws Exception {
        TransportEndpoint local = TransportEndpoint.udp(new InetSocketAddress(InetAddress.getLoopbackAddress(), 5070));
        TransportEndpoint remote = TransportEndpoint.udp(new InetSocketAddress(InetAddress.getLoopbackAddress(), 5071));
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
            SubscriptionClient client = new SubscriptionClient(transactions,
                    new SubscriptionRequestProfile(ViaTransport.UDP, new SentBy("client.example.com", 5070),
                            TransportProtocol.UDP, true), () -> "z9hG4bK-refresh");
            client.refresh(new SubscriptionRefreshRequest(
                    new SubscriptionId("subscription@example.com", "local-tag", "remote-tag",
                            new EventHeaderValue("presence", Optional.empty())),
                    SipUri.parse("sip:bob@example.com"), SipUri.parse("sip:alice@example.com"),
                    SipUri.parse("sip:bob@example.com"), 2, new ExpiresHeaderValue(0),
                    SipHeaders.empty(), SipBody.empty(), remote
            ));

            assertEquals("<sip:alice@example.com>;tag=local-tag", sent.get().headers().firstValue("From").orElseThrow());
            assertEquals("<sip:bob@example.com>;tag=remote-tag", sent.get().headers().firstValue("To").orElseThrow());
            assertEquals("2 SUBSCRIBE", sent.get().headers().firstValue("CSeq").orElseThrow());
            assertEquals("0", sent.get().headers().firstValue("Expires").orElseThrow());
        } finally {
            scheduler.close();
        }
    }

    private static InitialSubscriptionRequest request(TransportEndpoint target) {
        return new InitialSubscriptionRequest(
                SipUri.parse("sip:bob@example.com"), SipUri.parse("sip:alice@example.com"),
                SipUri.parse("sip:bob@example.com"), "subscription@example.com", "local-tag", 1,
                new EventHeaderValue("presence", Optional.of("watcher")), new ExpiresHeaderValue(3600),
                SipHeaders.empty(), SipBody.empty(), target
        );
    }
}
