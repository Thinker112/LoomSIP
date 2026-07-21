package org.loomsip.transaction.noninvite;

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
import org.loomsip.transaction.timer.SipTimer;
import org.loomsip.transaction.timer.SipTimerConfig;
import org.loomsip.transaction.timer.VirtualSipScheduler;
import org.loomsip.transport.InboundSipMessage;
import org.loomsip.transport.SendResult;
import org.loomsip.transport.TransportContext;
import org.loomsip.transport.TransportEndpoint;
import org.loomsip.transport.TransportProtocol;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Timeout(10)
class NonInviteTransactionManagerTest {

    private static final SipTimerConfig TIMERS = new SipTimerConfig(
            Duration.ofMillis(100),
            Duration.ofMillis(800),
            Duration.ofSeconds(1)
    );

    @Test
    void completesOptionsFlowAndCleansUpWithTimersKAndJ() throws Exception {
        try (TestRig rig = new TestRig((transaction, request) -> transaction.sendResponse(ok(request)))) {
            ClientTransactionHandle client = rig.sendOptions();

            assertEquals(List.of(200), rig.client.statuses);
            assertEquals(1, rig.server.requestCount.get());
            assertEquals(NonInviteClientState.COMPLETED, client.state());
            assertEquals(1, rig.clientManager.activeClientTransactions());
            assertEquals(1, rig.serverManager.activeServerTransactions());

            rig.scheduler.advanceBy(TIMERS.t4());
            assertEquals(NonInviteClientState.TERMINATED, client.state());
            assertEquals(0, rig.clientManager.activeClientTransactions());
            assertEquals(1, rig.serverManager.activeServerTransactions());

            rig.scheduler.advanceBy(TIMERS.sixtyFourT1().minus(TIMERS.t4()));
            assertEquals(0, rig.serverManager.activeServerTransactions());
            assertTrue(client.terminated().toCompletableFuture().isDone());
        }
    }

    @Test
    void timerERetransmitsWhenFirstRequestIsDropped() throws Exception {
        try (TestRig rig = new TestRig((transaction, request) -> transaction.sendResponse(ok(request)))) {
            rig.network.dropWhen(transmission -> transmission.message() instanceof SipRequest
                    && transmission.typeAttempt() == 1);

            ClientTransactionHandle client = rig.sendOptions();
            assertEquals(NonInviteClientState.TRYING, client.state());
            assertEquals(0, rig.server.requestCount.get());

            rig.scheduler.advanceBy(TIMERS.t1());

            assertEquals(2, rig.network.requestAttempts());
            assertEquals(1, rig.server.requestCount.get());
            assertEquals(List.of(200), rig.client.statuses);
            assertEquals(NonInviteClientState.COMPLETED, client.state());
        }
    }

    @Test
    void duplicateRequestResendsDroppedFinalResponseWithoutSecondTuDelivery() throws Exception {
        try (TestRig rig = new TestRig((transaction, request) -> transaction.sendResponse(ok(request)))) {
            rig.network.dropWhen(transmission -> transmission.message() instanceof SipResponse
                    && transmission.typeAttempt() == 1);

            ClientTransactionHandle client = rig.sendOptions();
            assertEquals(NonInviteClientState.TRYING, client.state());
            assertEquals(1, rig.server.requestCount.get());
            assertEquals(1, rig.network.responseAttempts());

            rig.scheduler.advanceBy(TIMERS.t1());

            assertEquals(2, rig.network.requestAttempts());
            assertEquals(2, rig.network.responseAttempts());
            assertEquals(1, rig.server.requestCount.get());
            assertEquals(List.of(200), rig.client.statuses);
            assertEquals(NonInviteClientState.COMPLETED, client.state());
        }
    }

    @Test
    void timerFTimesOutAfterControlledPacketLoss() throws Exception {
        try (TestRig rig = new TestRig((transaction, request) -> {
        })) {
            rig.network.dropWhen(transmission -> transmission.message() instanceof SipRequest);

            ClientTransactionHandle client = rig.sendOptions();
            rig.scheduler.advanceBy(TIMERS.sixtyFourT1());

            assertEquals(List.of(SipTimer.F), rig.client.timeouts);
            assertEquals(NonInviteClientState.TERMINATED, client.state());
            assertEquals(0, rig.clientManager.activeClientTransactions());
            assertTrue(rig.network.requestAttempts() > 1);
        }
    }

    @Test
    void deliversProvisionalThenFinalResponseInOrder() throws Exception {
        try (TestRig rig = new TestRig((transaction, request) -> {
            transaction.sendResponse(SipResponses.createResponse(request, 180, "Ringing"));
            transaction.sendResponse(ok(request));
        })) {
            ClientTransactionHandle client = rig.sendOptions();

            assertEquals(List.of(180, 200), rig.client.statuses);
            assertEquals(NonInviteClientState.COMPLETED, client.state());
            assertEquals(1, rig.server.requestCount.get());
        }
    }

    @Test
    void reportsTransportFailureAndRemovesClientTransaction() throws Exception {
        try (TestRig rig = new TestRig((transaction, request) -> {
        })) {
            rig.network.failWhen(transmission -> transmission.message() instanceof SipRequest);

            ClientTransactionHandle client = rig.sendOptions();

            assertEquals(NonInviteClientState.TERMINATED, client.state());
            assertEquals(0, rig.clientManager.activeClientTransactions());
            assertEquals(1, rig.client.transportFailures.size());
            assertEquals("simulated transport failure", rig.client.transportFailures.getFirst().getMessage());
        }
    }

    @Test
    void reliableServerWaitsForFinalWriteAndReportsItsFailure() throws Exception {
        VirtualSipScheduler scheduler = new VirtualSipScheduler();
        TransportEndpoint clientEndpoint = new TransportEndpoint(
                TransportProtocol.TCP,
                new InetSocketAddress(InetAddress.getLoopbackAddress(), 16060)
        );
        TransportEndpoint serverEndpoint = new TransportEndpoint(
                TransportProtocol.TCP,
                new InetSocketAddress(InetAddress.getLoopbackAddress(), 16061)
        );
        CompletableFuture<SendResult> responseWrite = new CompletableFuture<>();
        AtomicReference<ServerTransactionHandle> serverTransaction = new AtomicReference<>();
        List<Throwable> transportFailures = new CopyOnWriteArrayList<>();
        NonInviteClientListener ignoredClient = (transaction, response, context) -> {
        };
        NonInviteServerListener serverListener = new NonInviteServerListener() {
            @Override
            public void onRequest(
                    ServerTransactionHandle transaction,
                    SipRequest request,
                    TransportContext context
            ) {
                serverTransaction.set(transaction);
                transaction.sendResponse(ok(request));
            }

            @Override
            public void onTransportFailure(ServerTransactionHandle transaction, Throwable cause) {
                transportFailures.add(cause);
            }
        };

        try (NonInviteTransactionManager manager = new NonInviteTransactionManager(
                (message, target) -> responseWrite,
                TIMERS,
                NonInviteTransactionConfig.DEFAULT,
                ignoredClient,
                serverListener,
                scheduler,
                Runnable::run,
                Runnable::run
        )) {
            manager.onMessage(new InboundSipMessage(
                    optionsRequest(clientEndpoint),
                    new TransportContext(
                            TransportProtocol.TCP,
                            serverEndpoint.address(),
                            clientEndpoint.address()
                    )
            ));
            ServerTransactionHandle transaction = serverTransaction.get();

            assertEquals(NonInviteServerState.COMPLETED, transaction.state());
            assertEquals(1, manager.activeServerTransactions());

            responseWrite.completeExceptionally(new IllegalStateException("reliable write failed"));

            assertEquals(NonInviteServerState.TERMINATED, transaction.state());
            assertEquals(0, manager.activeServerTransactions());
            assertEquals(1, transportFailures.size());
            assertEquals("reliable write failed", transportFailures.getFirst().getMessage());
        } finally {
            scheduler.close();
        }
    }

    @Test
    void closeTerminatesAndRemovesActiveClientAndServerTransactions() throws Exception {
        try (TestRig rig = new TestRig((transaction, request) -> {
        })) {
            ClientTransactionHandle client = rig.sendOptions();
            assertEquals(1, rig.clientManager.activeClientTransactions());
            assertEquals(1, rig.serverManager.activeServerTransactions());

            rig.clientManager.close();
            rig.serverManager.close();

            assertEquals(NonInviteClientState.TERMINATED, client.state());
            assertEquals(0, rig.clientManager.activeClientTransactions());
            assertEquals(0, rig.serverManager.activeServerTransactions());
            assertTrue(client.terminated().toCompletableFuture().isDone());
        }
    }

    @Test
    void lateResponseAfterClientManagerCloseIsIgnored() throws Exception {
        try (TestRig rig = new TestRig((transaction, request) -> {
        })) {
            ClientTransactionHandle client = rig.sendOptions();
            rig.clientManager.close();

            rig.clientManager.onMessage(new InboundSipMessage(
                    ok(client.originalRequest()),
                    new TransportContext(
                            TransportProtocol.UDP,
                            rig.clientEndpoint.address(),
                            rig.serverEndpoint.address()
                    )
            ));

            assertEquals(NonInviteClientState.TERMINATED, client.state());
            assertEquals(0, rig.clientManager.activeClientTransactions());
            assertEquals(List.of(), rig.client.statuses);
            assertEquals(List.of(), rig.client.layerErrors);
        }
    }

    private static SipResponse ok(SipRequest request) {
        return SipResponses.createResponse(request, 200, "OK", "server-tag");
    }

    private static SipRequest optionsRequest(TransportEndpoint local) {
        String host = local.address().getAddress().getHostAddress();
        SipHeaders headers = SipHeaders.builder()
                .add("Via", "SIP/2.0/" + local.protocol() + " " + host + ":" + local.address().getPort()
                        + ";branch=z9hG4bK-3b-test;rport")
                .add("Max-Forwards", "70")
                .add("From", "<sip:alice@example.com>;tag=client-tag")
                .add("To", "<sip:bob@example.com>")
                .add("Call-ID", "3b-test@example.com")
                .add("CSeq", "1 OPTIONS")
                .build();
        return new SipRequest(SipMethod.OPTIONS, SipUri.parse("sip:bob@example.com"), headers);
    }

    private static TransportEndpoint endpoint(int port) throws Exception {
        return TransportEndpoint.udp(new InetSocketAddress(InetAddress.getLoopbackAddress(), port));
    }

    private static final class TestRig implements AutoCloseable {

        private final TransportEndpoint clientEndpoint;
        private final TransportEndpoint serverEndpoint;
        private final VirtualSipScheduler scheduler = new VirtualSipScheduler();
        private final ControlledNetwork network;
        private final RecordingClientListener client = new RecordingClientListener();
        private final RecordingServerListener server;
        private final NonInviteTransactionManager clientManager;
        private final NonInviteTransactionManager serverManager;

        private TestRig(ServerBehavior behavior) throws Exception {
            clientEndpoint = endpoint(15060);
            serverEndpoint = endpoint(15061);
            network = new ControlledNetwork(clientEndpoint, serverEndpoint);
            server = new RecordingServerListener(behavior);
            NonInviteClientListener ignoredClient = (transaction, response, context) -> {
            };
            NonInviteServerListener ignoredServer = (transaction, request, context) -> {
            };
            clientManager = new NonInviteTransactionManager(
                    network,
                    TIMERS,
                    NonInviteTransactionConfig.DEFAULT,
                    client,
                    ignoredServer,
                    scheduler,
                    Runnable::run,
                    Runnable::run
            );
            serverManager = new NonInviteTransactionManager(
                    network,
                    TIMERS,
                    NonInviteTransactionConfig.DEFAULT,
                    ignoredClient,
                    server,
                    scheduler,
                    Runnable::run,
                    Runnable::run
            );
            network.route(clientEndpoint, clientManager::onMessage);
            network.route(serverEndpoint, serverManager::onMessage);
        }

        private ClientTransactionHandle sendOptions() throws Exception {
            return clientManager.sendRequest(optionsRequest(clientEndpoint), serverEndpoint);
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
        void onRequest(ServerTransactionHandle transaction, SipRequest request);
    }

    private static final class RecordingClientListener implements NonInviteClientListener {

        private final List<Integer> statuses = new CopyOnWriteArrayList<>();
        private final List<SipTimer> timeouts = new CopyOnWriteArrayList<>();
        private final List<Throwable> transportFailures = new CopyOnWriteArrayList<>();
        private final List<Throwable> layerErrors = new CopyOnWriteArrayList<>();

        @Override
        public void onResponse(
                ClientTransactionHandle transaction,
                SipResponse response,
                TransportContext context
        ) {
            statuses.add(response.statusCode());
        }

        @Override
        public void onTimeout(ClientTransactionHandle transaction, SipTimer timer) {
            timeouts.add(timer);
        }

        @Override
        public void onTransportFailure(ClientTransactionHandle transaction, Throwable cause) {
            transportFailures.add(cause);
        }

        @Override
        public void onLayerError(Throwable cause) {
            layerErrors.add(cause);
        }
    }

    private static final class RecordingServerListener implements NonInviteServerListener {

        private final ServerBehavior behavior;
        private final AtomicInteger requestCount = new AtomicInteger();

        private RecordingServerListener(ServerBehavior behavior) {
            this.behavior = behavior;
        }

        @Override
        public void onRequest(
                ServerTransactionHandle transaction,
                SipRequest request,
                TransportContext context
        ) {
            requestCount.incrementAndGet();
            behavior.onRequest(transaction, request);
        }
    }

    private static final class ControlledNetwork implements TransactionMessageSender {

        private final TransportEndpoint clientEndpoint;
        private final TransportEndpoint serverEndpoint;
        private final List<Route> routes = new ArrayList<>();
        private final AtomicInteger requestAttempts = new AtomicInteger();
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

        private int requestAttempts() {
            return requestAttempts.get();
        }

        private int responseAttempts() {
            return responseAttempts.get();
        }

        @Override
        public CompletionStage<SendResult> send(SipMessage message, TransportEndpoint target) {
            int typeAttempt = message instanceof SipRequest
                    ? requestAttempts.incrementAndGet()
                    : responseAttempts.incrementAndGet();
            TransportEndpoint source = target.equals(serverEndpoint) ? clientEndpoint : serverEndpoint;
            Transmission transmission = new Transmission(message, source, target, typeAttempt);
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
                        new TransportContext(
                                target.protocol(),
                                target.address(),
                                source.address()
                        )
                ));
            }
            return CompletableFuture.completedFuture(new SendResult(source, target, 1));
        }
    }

    private record Route(TransportEndpoint endpoint, Consumer<InboundSipMessage> handler) {
    }

    private record Transmission(
            SipMessage message,
            TransportEndpoint source,
            TransportEndpoint target,
            int typeAttempt
    ) {
    }
}
