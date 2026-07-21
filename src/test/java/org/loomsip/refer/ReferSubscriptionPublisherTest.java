package org.loomsip.refer;

import org.junit.jupiter.api.Test;
import org.loomsip.message.SipHeaders;
import org.loomsip.message.SipRequest;
import org.loomsip.message.SipUri;
import org.loomsip.message.header.EventHeaderValue;
import org.loomsip.message.header.SentBy;
import org.loomsip.message.header.ViaTransport;
import org.loomsip.subscription.SubscriptionConfig;
import org.loomsip.subscription.SubscriptionHandle;
import org.loomsip.subscription.SubscriptionId;
import org.loomsip.subscription.SubscriptionManager;
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
import java.util.ArrayDeque;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ReferSubscriptionPublisherTest {

    @Test
    void serializesProgressThenOneFinalNotifyAndTerminatesSubscription() throws Exception {
        Fixture fixture = new Fixture();
        try {
            fixture.publisher.publish(new SipfragStatus(100, "Trying")).toCompletableFuture().join();
            fixture.publisher.publish(new SipfragStatus(200, "OK")).toCompletableFuture().join();

            assertEquals(List.of("1 NOTIFY", "2 NOTIFY"), fixture.sent.stream()
                    .map(request -> request.headers().firstValue("CSeq").orElseThrow()).toList());
            assertEquals("active", fixture.sent.getFirst().headers().firstValue("Subscription-State").orElseThrow());
            assertEquals("terminated;reason=noresource",
                    fixture.sent.get(1).headers().firstValue("Subscription-State").orElseThrow());
            assertEquals(0, fixture.manager.size());
            assertThrows(Exception.class, () -> fixture.publisher.publish(new SipfragStatus(486, "Busy Here"))
                    .toCompletableFuture().join());
        } finally {
            fixture.close();
        }
    }

    @Test
    void managerTerminationClosesPublisherAndRejectsLateProgress() throws Exception {
        Fixture fixture = new Fixture();
        try {
            fixture.manager.terminate(fixture.id, org.loomsip.subscription.SubscriptionTerminationReason.MANAGER_CLOSED)
                    .toCompletableFuture().join();

            assertThrows(Exception.class, () -> fixture.publisher.publish(new SipfragStatus(180, "Ringing"))
                    .toCompletableFuture().join());
            assertEquals(0, fixture.sent.size());
        } finally {
            fixture.close();
        }
    }

    @Test
    void queuedTerminalEventsCreateOnlyOneFinalNotify() throws Exception {
        ManualExecutor executor = new ManualExecutor();
        Fixture fixture = new Fixture(executor, SipHeaders.empty());
        try {
            var first = fixture.publisher.publish(new SipfragStatus(200, "OK"));
            var second = fixture.publisher.publish(new SipfragStatus(486, "Busy Here"));

            executor.runAll();

            first.toCompletableFuture().join();
            assertThrows(Exception.class, () -> second.toCompletableFuture().join());
            assertEquals(1, fixture.sent.size());
            assertEquals(0, fixture.manager.size());
        } finally {
            fixture.close();
        }
    }

    @Test
    void finalPublishConstructionFailureTerminatesSubscription() throws Exception {
        Fixture fixture = new Fixture(Runnable::run, SipHeaders.builder().add("Content-Type", "text/plain").build());
        try {
            assertThrows(Exception.class, () -> fixture.publisher.publish(new SipfragStatus(503, "Service Unavailable"))
                    .toCompletableFuture().join());
            assertEquals(0, fixture.manager.size());
            assertEquals(0, fixture.sent.size());
        } finally {
            fixture.close();
        }
    }

    private static final class Fixture implements AutoCloseable {
        private final VirtualSipScheduler scheduler = new VirtualSipScheduler();
        private final List<SipRequest> sent = new CopyOnWriteArrayList<>();
        private final TransportEndpoint local;
        private final TransportEndpoint remote;
        private final NonInviteTransactionManager transactions;
        private final SubscriptionManager manager;
        private final SubscriptionId id;
        private final ReferSubscriptionPublisher publisher;
        private final AtomicInteger branches = new AtomicInteger();

        private Fixture() throws Exception {
            this(Runnable::run, SipHeaders.empty());
        }

        private Fixture(Executor executor, SipHeaders additionalHeaders) throws Exception {
            local = TransportEndpoint.udp(new InetSocketAddress(InetAddress.getLoopbackAddress(), 5080));
            remote = TransportEndpoint.udp(new InetSocketAddress(InetAddress.getLoopbackAddress(), 5081));
            transactions = new NonInviteTransactionManager(
                    (message, target) -> {
                        sent.add((SipRequest) message);
                        return CompletableFuture.completedFuture(new SendResult(local, target, 1));
                    }, SipTimerConfig.DEFAULT, NonInviteTransactionConfig.DEFAULT,
                    (transaction, response, context) -> { }, (transaction, request, context) -> { },
                    scheduler, Runnable::run, Runnable::run
            );
            manager = new SubscriptionManager(SubscriptionConfig.DEFAULT, Runnable::run, failure -> {
                throw new AssertionError(failure);
            });
            id = new SubscriptionId("refer@example.com", "server-tag", "client-tag",
                    new EventHeaderValue("refer", Optional.empty()));
            SubscriptionHandle subscription = manager.create(id);
            manager.activate(id).toCompletableFuture().join();
            publisher = new ReferSubscriptionPublisher(manager, subscription,
                    new ReferNotifier(new SubscriptionPublisher(transactions,
                            new SubscriptionRequestProfile(ViaTransport.UDP, new SentBy("server.example.com", 5080),
                                    TransportProtocol.UDP, true), () -> "z9hG4bK-refer-" + branches.incrementAndGet())),
                    new ReferSubscriptionProfile(SipUri.parse("sip:client@example.com"),
                            SipUri.parse("sip:server@example.com"), SipUri.parse("sip:client@example.com"), 1,
                            additionalHeaders, remote),
                    executor, failure -> { throw new AssertionError(failure); }, 8);
        }

        @Override
        public void close() {
            publisher.close();
            manager.close();
            transactions.close();
            scheduler.close();
        }
    }

    private static final class ManualExecutor implements Executor {
        private final ArrayDeque<Runnable> tasks = new ArrayDeque<>();

        @Override
        public void execute(Runnable command) {
            tasks.addLast(command);
        }

        private void runAll() {
            while (!tasks.isEmpty()) {
                tasks.removeFirst().run();
            }
        }
    }
}
