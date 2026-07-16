package org.loomsip.transaction.invite;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.loomsip.message.SipHeaders;
import org.loomsip.message.SipMessage;
import org.loomsip.message.SipMethod;
import org.loomsip.message.SipRequest;
import org.loomsip.message.SipResponse;
import org.loomsip.message.SipResponses;
import org.loomsip.message.SipUri;
import org.loomsip.transaction.TransactionMessageSender;
import org.loomsip.transaction.TransactionRepositoryException;
import org.loomsip.transaction.timer.SipTimer;
import org.loomsip.transaction.timer.SipTimerConfig;
import org.loomsip.transaction.timer.VirtualSipScheduler;
import org.loomsip.transport.InboundSipMessage;
import org.loomsip.transport.SendResult;
import org.loomsip.transport.TransportContext;
import org.loomsip.transport.TransportEndpoint;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Timeout(10)
class InviteTransactionManagerTest {

    private static final SipTimerConfig TIMERS = new SipTimerConfig(
            Duration.ofMillis(100),
            Duration.ofMillis(800),
            Duration.ofSeconds(1)
    );

    @Test
    void completesProvisionalAndNon2xxFlowWithAckAndTimersDAndI() throws Exception {
        try (TestRig rig = new TestRig((transaction, invite) -> {
            transaction.sendResponse(SipResponses.createResponse(invite, 180, "Ringing"));
            transaction.sendResponse(busy(invite));
        })) {
            InviteClientHandle client = rig.sendInvite();
            InviteServerHandle server = rig.server.lastTransaction;

            assertEquals(List.of(180, 486), rig.client.statuses());
            assertEquals(1, rig.network.inviteAttempts());
            assertEquals(1, rig.network.ackAttempts());
            assertEquals(1, rig.server.ackCount.get());
            assertEquals(InviteClientState.COMPLETED, client.state());
            assertEquals(InviteServerState.CONFIRMED, server.state());

            SipRequest duplicateAck = InviteAcknowledgements.createNon2xxAck(
                    rig.invite,
                    rig.client.responses.getLast()
            );
            rig.network.send(duplicateAck, rig.serverEndpoint);
            assertEquals(1, rig.server.ackCount.get());
            assertEquals(0, rig.server.unmatchedAckCount.get());

            rig.scheduler.advanceBy(TIMERS.t4());
            assertEquals(InviteServerState.TERMINATED, server.state());
            assertEquals(0, rig.serverManager.activeServerTransactions());
            assertEquals(InviteClientState.COMPLETED, client.state());

            rig.scheduler.advanceBy(Duration.ofSeconds(32).minus(TIMERS.t4()));
            assertEquals(InviteClientState.TERMINATED, client.state());
            assertEquals(0, rig.clientManager.activeClientTransactions());
        }
    }

    @Test
    void timerARetransmitsDroppedInvite() throws Exception {
        try (TestRig rig = new TestRig((transaction, invite) -> transaction.sendResponse(busy(invite)))) {
            rig.network.dropWhen(transmission -> transmission.message() instanceof SipRequest request
                    && SipMethod.INVITE.equals(request.method())
                    && transmission.methodAttempt() == 1);

            InviteClientHandle client = rig.sendInvite();
            assertEquals(InviteClientState.CALLING, client.state());

            rig.scheduler.advanceBy(TIMERS.t1());

            assertEquals(2, rig.network.inviteAttempts());
            assertEquals(1, rig.server.inviteCount.get());
            assertEquals(List.of(486), rig.client.statuses());
            assertEquals(InviteClientState.COMPLETED, client.state());
        }
    }

    @Test
    void provisionalResponseStopsTimerAButTimerBStillExpires() throws Exception {
        try (TestRig rig = new TestRig((transaction, invite) ->
                transaction.sendResponse(SipResponses.createResponse(invite, 180, "Ringing")))) {
            InviteClientHandle client = rig.sendInvite();
            assertEquals(InviteClientState.PROCEEDING, client.state());

            rig.scheduler.advanceBy(TIMERS.t1().multipliedBy(4));
            assertEquals(1, rig.network.inviteAttempts());
            assertEquals(List.of(180), rig.client.statuses());

            rig.scheduler.advanceBy(TIMERS.sixtyFourT1().minus(TIMERS.t1().multipliedBy(4)));
            assertEquals(List.of(SipTimer.B), rig.client.timeouts);
            assertEquals(InviteClientState.TERMINATED, client.state());
        }
    }

    @Test
    void timerBTimesOutWhenAllInvitesAreLost() throws Exception {
        try (TestRig rig = new TestRig((transaction, invite) -> {
        })) {
            rig.network.dropWhen(transmission -> transmission.message() instanceof SipRequest request
                    && SipMethod.INVITE.equals(request.method()));

            InviteClientHandle client = rig.sendInvite();
            rig.scheduler.advanceBy(TIMERS.sixtyFourT1());

            assertEquals(List.of(SipTimer.B), rig.client.timeouts);
            assertEquals(InviteClientState.TERMINATED, client.state());
            assertEquals(0, rig.clientManager.activeClientTransactions());
            assertTrue(rig.network.inviteAttempts() > 1);
        }
    }

    @Test
    void timerGRetransmitsFinalResponseAndClientResendsAckWithoutSecondTuResponse() throws Exception {
        try (TestRig rig = new TestRig((transaction, invite) -> transaction.sendResponse(busy(invite)))) {
            rig.network.dropWhen(transmission -> transmission.message() instanceof SipRequest request
                    && SipMethod.ACK.equals(request.method())
                    && transmission.methodAttempt() == 1);

            InviteClientHandle client = rig.sendInvite();
            InviteServerHandle server = rig.server.lastTransaction;
            assertEquals(InviteServerState.COMPLETED, server.state());
            assertEquals(1, rig.network.ackAttempts());

            rig.scheduler.advanceBy(TIMERS.t1());

            assertEquals(2, rig.network.responseAttempts());
            assertEquals(2, rig.network.ackAttempts());
            assertEquals(List.of(486), rig.client.statuses());
            assertEquals(InviteClientState.COMPLETED, client.state());
            assertEquals(InviteServerState.CONFIRMED, server.state());
        }
    }

    @Test
    void timerHExpiresWhenEveryAckIsLost() throws Exception {
        try (TestRig rig = new TestRig((transaction, invite) -> transaction.sendResponse(busy(invite)))) {
            rig.network.dropWhen(transmission -> transmission.message() instanceof SipRequest request
                    && SipMethod.ACK.equals(request.method()));

            rig.sendInvite();
            InviteServerHandle server = rig.server.lastTransaction;
            rig.scheduler.advanceBy(TIMERS.sixtyFourT1());

            assertEquals(List.of(SipTimer.H), rig.server.timeouts);
            assertEquals(InviteServerState.TERMINATED, server.state());
            assertEquals(0, rig.serverManager.activeServerTransactions());
            assertTrue(rig.network.responseAttempts() > 1);
        }
    }

    @Test
    void duplicateInviteResendsLastResponseWithoutSecondTuDelivery() throws Exception {
        try (TestRig rig = new TestRig((transaction, invite) ->
                transaction.sendResponse(SipResponses.createResponse(invite, 180, "Ringing")))) {
            rig.network.dropWhen(transmission -> transmission.message() instanceof SipResponse);

            rig.sendInvite();
            rig.scheduler.advanceBy(TIMERS.t1());

            assertEquals(2, rig.network.inviteAttempts());
            assertEquals(2, rig.network.responseAttempts());
            assertEquals(1, rig.server.inviteCount.get());
        }
    }

    @Test
    void acceptedDeliversAdditional2xxAndTimersMAndLCleanUp() throws Exception {
        try (TestRig rig = new TestRig((transaction, invite) ->
                transaction.sendResponse(SipResponses.createResponse(invite, 200, "OK", "server-tag")))) {
            InviteClientHandle client = rig.sendInvite();
            SipResponse response = rig.client.responses.getFirst();

            assertEquals(InviteClientState.ACCEPTED, client.state());
            assertEquals(InviteServerState.ACCEPTED, rig.server.lastTransaction.state());
            assertEquals(1, rig.clientManager.activeClientTransactions());
            assertEquals(1, rig.serverManager.activeServerTransactions());
            assertEquals(0, rig.network.ackAttempts());

            rig.network.send(ackFor2xx(rig.invite, response), rig.serverEndpoint);
            assertEquals(1, rig.server.unmatchedAckCount.get());

            SipResponse forked = SipResponses.createResponse(rig.invite, 200, "OK", "forked-server-tag");
            rig.network.send(forked, rig.clientEndpoint);
            assertEquals(List.of(200, 200), rig.client.statuses());

            rig.scheduler.advanceBy(TIMERS.sixtyFourT1());
            assertEquals(InviteClientState.TERMINATED, client.state());
            assertEquals(InviteServerState.TERMINATED, rig.server.lastTransaction.state());
            assertEquals(0, rig.clientManager.activeClientTransactions());
            assertEquals(0, rig.serverManager.activeServerTransactions());
        }
    }

    @Test
    void transportFailureTerminatesClientTransaction() throws Exception {
        try (TestRig rig = new TestRig((transaction, invite) -> {
        })) {
            rig.network.failWhen(transmission -> transmission.message() instanceof SipRequest request
                    && SipMethod.INVITE.equals(request.method()));

            InviteClientHandle client = rig.sendInvite();

            assertEquals(InviteClientState.TERMINATED, client.state());
            assertEquals(0, rig.clientManager.activeClientTransactions());
            assertEquals("simulated transport failure", rig.client.transportFailures.getFirst().getMessage());
        }
    }

    @Test
    void enforcesClientCapacityAndCloseRemovesActiveTransaction() throws Exception {
        VirtualSipScheduler scheduler = new VirtualSipScheduler();
        TransportEndpoint clientEndpoint = endpoint(17160);
        TransportEndpoint serverEndpoint = endpoint(17161);
        InviteClientListener clientListener = (transaction, response, context) -> {
        };
        InviteServerListener serverListener = (transaction, request, context) -> {
        };
        TransactionMessageSender sender = (message, target) -> CompletableFuture.completedFuture(
                new SendResult(clientEndpoint, target, 1)
        );
        InviteTransactionConfig capacityOne = new InviteTransactionConfig(1, 1, 32, 16);

        try (InviteTransactionManager manager = new InviteTransactionManager(
                sender,
                TIMERS,
                capacityOne,
                clientListener,
                serverListener,
                scheduler,
                Runnable::run,
                Runnable::run
        )) {
            InviteClientHandle first = manager.sendInvite(
                    invite(clientEndpoint, "capacity-one"),
                    serverEndpoint
            );

            assertThrows(TransactionRepositoryException.class, () -> manager.sendInvite(
                    invite(clientEndpoint, "capacity-two"),
                    serverEndpoint
            ));
            assertEquals(1, manager.activeClientTransactions());

            manager.close();
            assertEquals(InviteClientState.TERMINATED, first.state());
            assertEquals(0, manager.activeClientTransactions());
        } finally {
            scheduler.close();
        }
    }

    @Test
    void blockedTuCallbackDoesNotBlockTimerBOrRepositoryCleanup() throws Exception {
        VirtualSipScheduler scheduler = new VirtualSipScheduler();
        ExecutorService callbackExecutor = Executors.newSingleThreadExecutor();
        CountDownLatch callbackEntered = new CountDownLatch(1);
        CountDownLatch releaseCallback = new CountDownLatch(1);
        TransportEndpoint clientEndpoint = endpoint(17260);
        TransportEndpoint serverEndpoint = endpoint(17261);
        SipRequest invite = invite(clientEndpoint, "blocked-tu");
        InviteClientListener clientListener = (transaction, response, context) -> {
            callbackEntered.countDown();
            try {
                releaseCallback.await();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        };
        InviteServerListener serverListener = (transaction, request, context) -> {
        };
        TransactionMessageSender sender = (message, target) -> CompletableFuture.completedFuture(
                new SendResult(clientEndpoint, target, 1)
        );

        try (InviteTransactionManager manager = new InviteTransactionManager(
                sender,
                TIMERS,
                InviteTransactionConfig.DEFAULT,
                clientListener,
                serverListener,
                scheduler,
                Runnable::run,
                callbackExecutor
        )) {
            InviteClientHandle transaction = manager.sendInvite(invite, serverEndpoint);
            manager.onMessage(new InboundSipMessage(
                    SipResponses.createResponse(invite, 180, "Ringing"),
                    new TransportContext(
                            serverEndpoint.protocol(),
                            clientEndpoint.address(),
                            serverEndpoint.address()
                    )
            ));
            assertTrue(callbackEntered.await(2, TimeUnit.SECONDS));

            scheduler.advanceBy(TIMERS.sixtyFourT1());

            assertEquals(InviteClientState.TERMINATED, transaction.state());
            assertEquals(0, manager.activeClientTransactions());
        } finally {
            releaseCallback.countDown();
            callbackExecutor.shutdownNow();
            callbackExecutor.awaitTermination(2, TimeUnit.SECONDS);
            scheduler.close();
        }
    }

    private static SipResponse busy(SipRequest invite) {
        return SipResponses.createResponse(invite, 486, "Busy Here", "server-tag");
    }

    private static SipRequest invite(TransportEndpoint local) {
        return invite(local, "invite-test");
    }

    private static SipRequest invite(TransportEndpoint local, String identity) {
        String host = local.address().getAddress().getHostAddress();
        SipHeaders headers = SipHeaders.builder()
                .add("Via", "SIP/2.0/" + local.protocol() + " " + host + ":" + local.address().getPort()
                        + ";branch=z9hG4bK-" + identity + ";rport")
                .add("Max-Forwards", "70")
                .add("From", "<sip:alice@example.com>;tag=client-tag")
                .add("To", "<sip:bob@example.com>")
                .add("Call-ID", identity + "@example.com")
                .add("CSeq", "1 INVITE")
                .build();
        return new SipRequest(SipMethod.INVITE, SipUri.parse("sip:bob@example.com"), headers);
    }

    private static SipRequest ackFor2xx(SipRequest invite, SipResponse response) {
        SipHeaders headers = SipHeaders.builder()
                .add(invite.headers().first("Via").orElseThrow())
                .add(invite.headers().first("Max-Forwards").orElseThrow())
                .add(invite.headers().first("From").orElseThrow())
                .add(response.headers().first("To").orElseThrow())
                .add(invite.headers().first("Call-ID").orElseThrow())
                .add("CSeq", "1 ACK")
                .build();
        return new SipRequest(SipMethod.ACK, invite.requestUri(), headers);
    }

    private static TransportEndpoint endpoint(int port) throws Exception {
        return TransportEndpoint.udp(new InetSocketAddress(InetAddress.getLoopbackAddress(), port));
    }

    private static final class TestRig implements AutoCloseable {

        private final TransportEndpoint clientEndpoint;
        private final TransportEndpoint serverEndpoint;
        private final SipRequest invite;
        private final VirtualSipScheduler scheduler = new VirtualSipScheduler();
        private final ControlledNetwork network;
        private final RecordingClientListener client = new RecordingClientListener();
        private final RecordingServerListener server;
        private final InviteTransactionManager clientManager;
        private final InviteTransactionManager serverManager;

        private TestRig(ServerBehavior behavior) throws Exception {
            clientEndpoint = endpoint(17060);
            serverEndpoint = endpoint(17061);
            invite = invite(clientEndpoint);
            network = new ControlledNetwork(clientEndpoint, serverEndpoint);
            server = new RecordingServerListener(behavior);
            InviteClientListener ignoredClient = (transaction, response, context) -> {
            };
            InviteServerListener ignoredServer = (transaction, request, context) -> {
            };
            clientManager = new InviteTransactionManager(
                    network,
                    TIMERS,
                    InviteTransactionConfig.DEFAULT,
                    client,
                    ignoredServer,
                    scheduler,
                    Runnable::run,
                    Runnable::run
            );
            serverManager = new InviteTransactionManager(
                    network,
                    TIMERS,
                    InviteTransactionConfig.DEFAULT,
                    ignoredClient,
                    server,
                    scheduler,
                    Runnable::run,
                    Runnable::run
            );
            network.route(clientEndpoint, clientManager::onMessage);
            network.route(serverEndpoint, serverManager::onMessage);
        }

        private InviteClientHandle sendInvite() throws Exception {
            return clientManager.sendInvite(invite, serverEndpoint);
        }

        @Override
        public void close() {
            clientManager.close();
            serverManager.close();
            scheduler.close();
        }
    }

    @FunctionalInterface
    private interface ServerBehavior {
        void onInvite(InviteServerHandle transaction, SipRequest invite);
    }

    private static final class RecordingClientListener implements InviteClientListener {

        private final List<SipResponse> responses = new CopyOnWriteArrayList<>();
        private final List<SipTimer> timeouts = new CopyOnWriteArrayList<>();
        private final List<Throwable> transportFailures = new CopyOnWriteArrayList<>();

        private List<Integer> statuses() {
            return responses.stream().map(SipResponse::statusCode).toList();
        }

        @Override
        public void onResponse(
                InviteClientHandle transaction,
                SipResponse response,
                TransportContext context
        ) {
            responses.add(response);
        }

        @Override
        public void onTimeout(InviteClientHandle transaction, SipTimer timer) {
            timeouts.add(timer);
        }

        @Override
        public void onTransportFailure(InviteClientHandle transaction, Throwable cause) {
            transportFailures.add(cause);
        }
    }

    private static final class RecordingServerListener implements InviteServerListener {

        private final ServerBehavior behavior;
        private final AtomicInteger inviteCount = new AtomicInteger();
        private final AtomicInteger ackCount = new AtomicInteger();
        private final AtomicInteger unmatchedAckCount = new AtomicInteger();
        private final List<SipTimer> timeouts = new CopyOnWriteArrayList<>();
        private InviteServerHandle lastTransaction;

        private RecordingServerListener(ServerBehavior behavior) {
            this.behavior = behavior;
        }

        @Override
        public void onInvite(
                InviteServerHandle transaction,
                SipRequest invite,
                TransportContext context
        ) {
            inviteCount.incrementAndGet();
            lastTransaction = transaction;
            behavior.onInvite(transaction, invite);
        }

        @Override
        public void onUnmatchedAck(SipRequest ack, TransportContext context) {
            unmatchedAckCount.incrementAndGet();
        }

        @Override
        public void onAck(InviteServerHandle transaction, SipRequest ack, TransportContext context) {
            ackCount.incrementAndGet();
        }

        @Override
        public void onTimeout(InviteServerHandle transaction, SipTimer timer) {
            timeouts.add(timer);
        }
    }

    private static final class ControlledNetwork implements TransactionMessageSender {

        private final TransportEndpoint clientEndpoint;
        private final TransportEndpoint serverEndpoint;
        private final List<Route> routes = new ArrayList<>();
        private final AtomicInteger inviteAttempts = new AtomicInteger();
        private final AtomicInteger ackAttempts = new AtomicInteger();
        private final AtomicInteger responseAttempts = new AtomicInteger();
        private Predicate<Transmission> dropPredicate = transmission -> false;
        private Predicate<Transmission> failurePredicate = transmission -> false;

        private ControlledNetwork(TransportEndpoint clientEndpoint, TransportEndpoint serverEndpoint) {
            this.clientEndpoint = clientEndpoint;
            this.serverEndpoint = serverEndpoint;
        }

        private void route(TransportEndpoint endpoint, Consumer<InboundSipMessage> handler) {
            routes.add(new Route(endpoint, handler));
        }

        private void dropWhen(Predicate<Transmission> predicate) {
            dropPredicate = predicate;
        }

        private void failWhen(Predicate<Transmission> predicate) {
            failurePredicate = predicate;
        }

        private int inviteAttempts() {
            return inviteAttempts.get();
        }

        private int ackAttempts() {
            return ackAttempts.get();
        }

        private int responseAttempts() {
            return responseAttempts.get();
        }

        @Override
        public CompletionStage<SendResult> send(SipMessage message, TransportEndpoint target) {
            int methodAttempt = incrementAttempt(message);
            TransportEndpoint source = target.equals(serverEndpoint) ? clientEndpoint : serverEndpoint;
            Transmission transmission = new Transmission(message, source, target, methodAttempt);
            if (failurePredicate.test(transmission)) {
                return CompletableFuture.failedFuture(new IllegalStateException("simulated transport failure"));
            }
            if (!dropPredicate.test(transmission)) {
                Route route = routes.stream()
                        .filter(candidate -> candidate.endpoint().equals(target))
                        .findFirst()
                        .orElseThrow();
                route.handler().accept(new InboundSipMessage(
                        message,
                        new TransportContext(target.protocol(), target.address(), source.address())
                ));
            }
            return CompletableFuture.completedFuture(new SendResult(source, target, 1));
        }

        private int incrementAttempt(SipMessage message) {
            if (message instanceof SipResponse) {
                return responseAttempts.incrementAndGet();
            }
            SipRequest request = (SipRequest) message;
            return SipMethod.ACK.equals(request.method())
                    ? ackAttempts.incrementAndGet()
                    : inviteAttempts.incrementAndGet();
        }
    }

    private record Route(TransportEndpoint endpoint, Consumer<InboundSipMessage> handler) {
    }

    private record Transmission(
            SipMessage message,
            TransportEndpoint source,
            TransportEndpoint target,
            int methodAttempt
    ) {
    }
}
