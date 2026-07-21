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
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SubscriptionFinalNotifierTest {

    @Test
    void expiryPublishesOneTimeoutNotifyAfterTheLatestDeadline() throws Exception {
        Fixture fixture = new Fixture();
        try {
            SubscriptionId id = fixture.id("presence");
            SubscriptionHandle handle = fixture.manager.create(id);
            fixture.notifier.register(handle, fixture.notification(id, 3));

            fixture.manager.refresh(id, new ExpiresHeaderValue(10)).toCompletableFuture().join();
            fixture.manager.refresh(id, new ExpiresHeaderValue(20)).toCompletableFuture().join();
            fixture.scheduler.advanceBy(Duration.ofSeconds(10));
            assertEquals(0, fixture.sent.size());

            fixture.scheduler.advanceBy(Duration.ofSeconds(10));
            fixture.scheduler.advanceBy(Duration.ofSeconds(30));
            assertEquals(1, uniqueTransactions(fixture.sent));
            assertEquals("terminated;reason=timeout", header(fixture.sent.getFirst(), "Subscription-State"));
            assertEquals("3 NOTIFY", header(fixture.sent.getFirst(), "CSeq"));
        } finally {
            fixture.close();
        }
    }

    @Test
    void zeroExpiresPublishesOneDeactivatedNotifyAndCloseCannotRepeatIt() throws Exception {
        Fixture fixture = new Fixture();
        try {
            SubscriptionId id = fixture.id("refer");
            SubscriptionHandle handle = fixture.manager.create(id);
            fixture.notifier.register(handle, fixture.notification(id, 4));

            fixture.manager.refresh(id, new ExpiresHeaderValue(0)).toCompletableFuture().join();
            fixture.manager.close();
            fixture.scheduler.advanceBy(Duration.ofDays(1));

            assertEquals(1, uniqueTransactions(fixture.sent));
            assertEquals("terminated;reason=deactivated", header(fixture.sent.getFirst(), "Subscription-State"));
        } finally {
            fixture.close();
        }
    }

    @Test
    void remoteTerminationAndManagerCloseDropFinalNotificationContext() throws Exception {
        Fixture fixture = new Fixture();
        try {
            SubscriptionId remoteId = fixture.id("presence");
            SubscriptionHandle remote = fixture.manager.create(remoteId);
            fixture.notifier.register(remote, fixture.notification(remoteId, 5));
            fixture.manager.terminate(remoteId, SubscriptionTerminationReason.REMOTE_TERMINATED).toCompletableFuture().join();

            SubscriptionId closeId = fixture.id("refer");
            SubscriptionHandle closing = fixture.manager.create(closeId);
            fixture.notifier.register(closing, fixture.notification(closeId, 6));
            fixture.manager.close();

            assertEquals(0, fixture.sent.size());
        } finally {
            fixture.close();
        }
    }

    private static String header(SipRequest request, String name) {
        return request.headers().firstValue(name).orElseThrow();
    }

    private static long uniqueTransactions(List<SipRequest> requests) {
        return requests.stream().map(request -> header(request, "Via")).distinct().count();
    }

    private static final class Fixture implements AutoCloseable {
        private final VirtualSipScheduler scheduler = new VirtualSipScheduler();
        private final List<SipRequest> sent = new CopyOnWriteArrayList<>();
        private final TransportEndpoint local;
        private final TransportEndpoint remote;
        private final NonInviteTransactionManager transactions;
        private final SubscriptionFinalNotifier notifier;
        private final SubscriptionManager manager;

        private Fixture() throws Exception {
            local = TransportEndpoint.udp(new InetSocketAddress(InetAddress.getLoopbackAddress(), 5080));
            remote = TransportEndpoint.udp(new InetSocketAddress(InetAddress.getLoopbackAddress(), 5081));
            transactions = new NonInviteTransactionManager(
                    (message, target) -> {
                        sent.add((SipRequest) message);
                        return CompletableFuture.completedFuture(new SendResult(local, target, 1));
                    },
                    SipTimerConfig.DEFAULT, NonInviteTransactionConfig.DEFAULT,
                    (transaction, response, context) -> { }, (transaction, request, context) -> { },
                    scheduler, Runnable::run, Runnable::run
            );
            notifier = new SubscriptionFinalNotifier(new SubscriptionPublisher(transactions,
                    new SubscriptionRequestProfile(ViaTransport.UDP, new SentBy("server.example.com", 5080),
                            TransportProtocol.UDP, true), () -> "z9hG4bK-final"), failure -> {
                        throw new AssertionError(failure);
                    });
            manager = new SubscriptionManager(SubscriptionConfig.DEFAULT, Runnable::run, failure -> {
                throw new AssertionError(failure);
            }, scheduler, notifier);
        }

        private SubscriptionId id(String event) {
            return new SubscriptionId("subscription@example.com", "server-tag", "client-tag",
                    new EventHeaderValue(event, Optional.empty()));
        }

        private SubscriptionFinalNotification notification(SubscriptionId id, long cseq) throws Exception {
            return new SubscriptionFinalNotification(id, SipUri.parse("sip:client@example.com"),
                    SipUri.parse("sip:server@example.com"), SipUri.parse("sip:client@example.com"), cseq,
                    SipHeaders.empty(), SipBody.empty(), remote);
        }

        @Override
        public void close() {
            manager.close();
            notifier.close();
            transactions.close();
            scheduler.close();
        }
    }
}
