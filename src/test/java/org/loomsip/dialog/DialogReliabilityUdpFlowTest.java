package org.loomsip.dialog;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.loomsip.message.SipHeaders;
import org.loomsip.message.SipMessage;
import org.loomsip.message.SipMethod;
import org.loomsip.message.SipRequest;
import org.loomsip.message.SipResponse;
import org.loomsip.message.SipResponses;
import org.loomsip.message.SipUri;
import org.loomsip.message.header.SipHeaderValues;
import org.loomsip.transaction.TransactionMessageSender;
import org.loomsip.transaction.invite.InviteClientHandle;
import org.loomsip.transaction.invite.InviteClientListener;
import org.loomsip.transaction.invite.InviteServerHandle;
import org.loomsip.transaction.invite.InviteServerListener;
import org.loomsip.transaction.invite.InviteTransactionConfig;
import org.loomsip.transaction.invite.InviteTransactionManager;
import org.loomsip.transaction.timer.SipTimerConfig;
import org.loomsip.transaction.timer.VirtualSipScheduler;
import org.loomsip.transport.InboundSipMessage;
import org.loomsip.transport.SendResult;
import org.loomsip.transport.TransportContext;
import org.loomsip.transport.TransportEndpoint;
import org.loomsip.transport.TransportProtocol;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Timeout(10)
class DialogReliabilityUdpFlowTest {

    private static final SipTimerConfig TIMERS = SipTimerConfig.DEFAULT;

    @Test
    void lostFirstAckIsRecoveredByRetransmitted2xxAndCachedAck() throws Exception {
        try (TestRig rig = new TestRig()) {
            rig.network.dropWhen(transmission -> transmission.message() instanceof SipRequest request
                    && SipMethod.ACK.equals(request.method())
                    && transmission.methodAttempt() == 1);

            rig.startCall();
            SipRequest firstAck = rig.network.acks.getFirst();
            assertEquals(1, rig.network.responseAttempts.get());
            assertEquals(0, rig.serverDialogListener.acks.get());

            rig.advanceBy(TIMERS.t1());

            assertEquals(2, rig.network.responseAttempts.get());
            assertEquals(2, rig.network.ackAttempts.get());
            assertEquals(firstAck, rig.network.acks.get(1));
            assertEquals(1, rig.serverDialogListener.acks.get());
            assertEquals(List.of(200, 200), rig.clientApplication.statuses);

            rig.advanceBy(TIMERS.t2());
            assertEquals(2, rig.network.responseAttempts.get());
        }
    }

    @Test
    void lostFirst2xxIsRecoveredByUasDialogRetransmission() throws Exception {
        try (TestRig rig = new TestRig()) {
            rig.network.dropWhen(transmission -> transmission.message() instanceof SipResponse
                    && transmission.methodAttempt() == 1);

            rig.startCall();
            assertTrue(rig.clientApplication.statuses.isEmpty());

            rig.advanceBy(TIMERS.t1());

            assertEquals(List.of(200), rig.clientApplication.statuses);
            assertEquals(2, rig.network.responseAttempts.get());
            assertEquals(1, rig.network.ackAttempts.get());
            assertEquals(1, rig.serverDialogListener.acks.get());
        }
    }

    @Test
    void repeatedAndForked2xxUsePerDialogCachedAcks() throws Exception {
        try (TestRig rig = new TestRig()) {
            InviteClientHandle transaction = rig.startCall();
            SipResponse original = rig.serverApplication.successResponse;

            rig.deliverToClient(original);
            SipResponse forked = success(rig.invite, "forked-tag");
            rig.deliverToClient(forked);

            List<SipRequest> acks = rig.network.acks;
            assertEquals(3, acks.size());
            assertEquals(acks.get(0), acks.get(1));
            assertNotEquals(acks.get(0), acks.get(2));
            assertEquals("server-tag", SipHeaderValues.toTag(acks.get(0).headers()).orElseThrow());
            assertEquals("forked-tag", SipHeaderValues.toTag(acks.get(2).headers()).orElseThrow());
            assertEquals(2, rig.clientDialogs.findBySet(
                    new DialogSetId("reliability-flow@example.com", "client-tag")
            ).size());
            assertEquals(org.loomsip.transaction.invite.InviteClientState.ACCEPTED,
                    transaction.state());
        }
    }

    @Test
    void ackTimeoutStopsRetransmissionAndKeepsConfirmedDialog() throws Exception {
        try (TestRig rig = new TestRig()) {
            rig.network.dropWhen(transmission -> transmission.message() instanceof SipRequest request
                    && SipMethod.ACK.equals(request.method()));

            rig.startCall();
            rig.advanceBy(TIMERS.sixtyFourT1());
            int responsesAtTimeout = rig.network.responseAttempts.get();

            assertTrue(responsesAtTimeout > 1);
            assertEquals(List.of(1L), rig.serverDialogListener.timeouts);
            assertEquals(DialogState.CONFIRMED, rig.serverDialog().snapshot().state());

            rig.advanceBy(TIMERS.t2().multipliedBy(2));
            assertEquals(responsesAtTimeout, rig.network.responseAttempts.get());
        }
    }

    @Test
    void uacAckWriteFailureIsReportedWithoutSuppressing2xxCallback() throws Exception {
        try (TestRig rig = new TestRig()) {
            rig.network.failWhen(transmission -> transmission.message() instanceof SipRequest request
                    && SipMethod.ACK.equals(request.method()));

            rig.startCall();

            assertEquals(List.of(200), rig.clientApplication.statuses);
            assertEquals(1, rig.clientApplication.errors.size());
            assertEquals(1, rig.clientDialogListener.reliabilityFailures.size());
            assertEquals(SipMethod.ACK,
                    ((SipRequest) rig.clientDialogListener.reliabilityFailures.getFirst()).method());
            assertEquals(0, rig.serverDialogListener.acks.get());
        }
    }

    @Test
    void failedInitialUas2xxCancelsDialogRetransmissionState() throws Exception {
        try (TestRig rig = new TestRig()) {
            rig.network.failWhen(transmission -> transmission.message() instanceof SipResponse);

            rig.startCall();
            rig.clientTransactions.close();
            rig.advanceBy(TIMERS.t1().multipliedBy(4));

            assertTrue(rig.serverApplication.errors.isEmpty(),
                    () -> rig.serverApplication.errors.toString());
            assertEquals(1, rig.serverApplication.transportFailures.get(), () ->
                    "responses=" + rig.network.responseAttempts.get()
                            + ", dialogFailures="
                            + rig.serverDialogListener.reliabilityFailures.size());
            assertEquals(1, rig.network.responseAttempts.get());
            assertTrue(rig.serverDialogListener.timeouts.isEmpty());
        }
    }

    private static SipRequest invite() {
        return new SipRequest(
                SipMethod.INVITE,
                SipUri.parse("sip:bob@example.com"),
                SipHeaders.builder()
                        .add("Via", "SIP/2.0/UDP client.example.com:5060"
                                + ";branch=z9hG4bK-reliability-flow;rport")
                        .add("Max-Forwards", "70")
                        .add("From", "<sip:alice@example.com>;tag=client-tag")
                        .add("To", "<sip:bob@example.com>")
                        .add("Call-ID", "reliability-flow@example.com")
                        .add("CSeq", "1 INVITE")
                        .add("Contact", "<sip:alice@client.example.com>")
                        .build()
        );
    }

    private static SipResponse success(SipRequest invite, String localTag) {
        SipResponse base = SipResponses.createResponse(invite, 200, "OK", localTag);
        return new SipResponse(
                base.version(),
                base.statusCode(),
                base.reasonPhrase(),
                base.headers().toBuilder()
                        .add("Contact", "<sip:bob@server.example.com>")
                        .build(),
                base.body()
        );
    }

    private static TransportEndpoint endpoint(int port) throws Exception {
        return TransportEndpoint.udp(new InetSocketAddress(InetAddress.getLoopbackAddress(), port));
    }

    private static final class TestRig implements AutoCloseable {

        private final VirtualSipScheduler scheduler = new VirtualSipScheduler();
        private final TransportEndpoint clientEndpoint;
        private final TransportEndpoint serverEndpoint;
        private final SipRequest invite = invite();
        private final ControlledNetwork network;
        private final RecordingClientApplication clientApplication = new RecordingClientApplication();
        private final RecordingServerApplication serverApplication = new RecordingServerApplication();
        private final RecordingDialogListener clientDialogListener = new RecordingDialogListener();
        private final RecordingDialogListener serverDialogListener = new RecordingDialogListener();
        private final DialogManager clientDialogs;
        private final DialogManager serverDialogs;
        private final InviteTransactionManager clientTransactions;
        private final InviteTransactionManager serverTransactions;

        private TestRig() throws Exception {
            clientEndpoint = endpoint(21060);
            serverEndpoint = endpoint(21061);
            network = new ControlledNetwork(clientEndpoint, serverEndpoint);
            AtomicInteger branches = new AtomicInteger();
            DialogRuntime clientRuntime = new DialogRuntime(
                    network,
                    (uri, protocol) -> CompletableFuture.completedFuture(serverEndpoint),
                    scheduler,
                    TIMERS,
                    () -> "z9hG4bK-client-ack-" + branches.incrementAndGet(),
                    Runnable::run
            );
            DialogRuntime serverRuntime = new DialogRuntime(
                    network,
                    (uri, protocol) -> CompletableFuture.completedFuture(clientEndpoint),
                    scheduler,
                    TIMERS,
                    () -> "z9hG4bK-server-unused",
                    Runnable::run
            );
            clientDialogs = manager(clientDialogListener, clientRuntime);
            serverDialogs = manager(serverDialogListener, serverRuntime);
            DialogTransactionBridge clientBridge = new DialogTransactionBridge(
                    clientDialogs,
                    clientApplication,
                    (transaction, request, context) -> {
                    }
            );
            DialogTransactionBridge serverBridge = new DialogTransactionBridge(
                    serverDialogs,
                    (transaction, response, context) -> {
                    },
                    serverApplication
            );
            clientTransactions = transactionManager(clientBridge);
            serverTransactions = transactionManager(serverBridge);
            network.route(clientEndpoint, clientTransactions::onMessage);
            network.route(serverEndpoint, serverTransactions::onMessage);
        }

        private InviteTransactionManager transactionManager(DialogTransactionBridge bridge) {
            return new InviteTransactionManager(
                    network,
                    TIMERS,
                    InviteTransactionConfig.DEFAULT,
                    bridge.clientListener(),
                    bridge.serverListener(),
                    scheduler,
                    Runnable::run,
                    Runnable::run
            );
        }

        private static DialogManager manager(
                DialogLifecycleListener listener,
                DialogRuntime runtime
        ) {
            return new DialogManager(
                    new DialogConfig(16, 128, 64),
                    listener,
                    new InMemoryDialogRepository(16),
                    Runnable::run,
                    Runnable::run,
                    runtime
            );
        }

        private InviteClientHandle startCall() throws Exception {
            InviteClientHandle transaction = clientTransactions.sendInvite(invite, serverEndpoint);
            network.drainDeliveries();
            return transaction;
        }

        private void advanceBy(java.time.Duration duration) {
            scheduler.advanceBy(duration);
            network.drainDeliveries();
        }

        private void deliverToClient(SipResponse response) {
            clientTransactions.onMessage(new InboundSipMessage(
                    response,
                    new TransportContext(
                            TransportProtocol.UDP,
                            clientEndpoint.address(),
                            serverEndpoint.address()
                    )
            ));
        }

        private DialogHandle serverDialog() {
            return serverDialogs.find(new DialogId(
                    "reliability-flow@example.com",
                    "server-tag",
                    "client-tag"
            )).orElseThrow();
        }

        @Override
        public void close() {
            clientTransactions.close();
            serverTransactions.close();
            clientDialogs.close();
            serverDialogs.close();
            scheduler.close();
        }
    }

    private static final class RecordingClientApplication implements InviteClientListener {

        private final List<Integer> statuses = new ArrayList<>();
        private final List<Throwable> errors = new ArrayList<>();

        @Override
        public void onResponse(
                InviteClientHandle transaction,
                SipResponse response,
                TransportContext context
        ) {
            statuses.add(response.statusCode());
        }

        @Override
        public void onLayerError(Throwable cause) {
            errors.add(cause);
        }
    }

    private static final class RecordingServerApplication implements InviteServerListener {

        private SipResponse successResponse;
        private final AtomicInteger transportFailures = new AtomicInteger();
        private final List<Throwable> errors = new ArrayList<>();

        @Override
        public void onInvite(
                InviteServerHandle transaction,
                SipRequest request,
                TransportContext context
        ) {
            successResponse = success(request, "server-tag");
            transaction.sendResponse(successResponse);
        }

        @Override
        public void onTransportFailure(InviteServerHandle transaction, Throwable cause) {
            transportFailures.incrementAndGet();
        }

        @Override
        public void onLayerError(Throwable cause) {
            errors.add(cause);
        }
    }

    private static final class RecordingDialogListener implements DialogLifecycleListener {

        private final AtomicInteger acks = new AtomicInteger();
        private final List<Long> timeouts = new ArrayList<>();
        private final List<SipMessage> reliabilityFailures = new ArrayList<>();

        @Override
        public void onAckReceived(DialogHandle dialog, SipRequest ack, TransportContext context) {
            acks.incrementAndGet();
        }

        @Override
        public void onAckTimeout(DialogHandle dialog, long inviteCSeq) {
            timeouts.add(inviteCSeq);
        }

        @Override
        public void onReliabilityTransportFailure(
                DialogHandle dialog,
                SipMessage message,
                Throwable cause
        ) {
            reliabilityFailures.add(message);
        }
    }

    private static final class ControlledNetwork implements TransactionMessageSender {

        private final TransportEndpoint clientEndpoint;
        private final TransportEndpoint serverEndpoint;
        private final List<Route> routes = new ArrayList<>();
        private final List<SipRequest> acks = new ArrayList<>();
        private final AtomicInteger inviteAttempts = new AtomicInteger();
        private final AtomicInteger responseAttempts = new AtomicInteger();
        private final AtomicInteger ackAttempts = new AtomicInteger();
        private final ArrayDeque<Runnable> deliveries = new ArrayDeque<>();
        private Predicate<Transmission> dropPredicate = ignored -> false;
        private Predicate<Transmission> failurePredicate = ignored -> false;
        private boolean delivering;

        private ControlledNetwork(
                TransportEndpoint clientEndpoint,
                TransportEndpoint serverEndpoint
        ) {
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

        @Override
        public CompletionStage<SendResult> send(SipMessage message, TransportEndpoint target) {
            int attempt = increment(message);
            TransportEndpoint source = target.equals(serverEndpoint) ? clientEndpoint : serverEndpoint;
            Transmission transmission = new Transmission(message, source, target, attempt);
            if (failurePredicate.test(transmission)) {
                return CompletableFuture.failedFuture(new IllegalStateException(
                        "simulated transport failure"
                ));
            }
            if (!dropPredicate.test(transmission)) {
                Route route = routes.stream()
                        .filter(candidate -> candidate.endpoint.equals(target))
                        .findFirst()
                        .orElseThrow();
                deliveries.addLast(() -> route.handler.accept(new InboundSipMessage(
                        message,
                        new TransportContext(target.protocol(), target.address(), source.address())
                )));
            }
            return CompletableFuture.completedFuture(new SendResult(source, target, 1));
        }

        private void drainDeliveries() {
            if (delivering) {
                return;
            }
            delivering = true;
            try {
                while (!deliveries.isEmpty()) {
                    deliveries.removeFirst().run();
                }
            } finally {
                delivering = false;
            }
        }

        private int increment(SipMessage message) {
            if (message instanceof SipResponse) {
                return responseAttempts.incrementAndGet();
            }
            SipRequest request = (SipRequest) message;
            if (SipMethod.ACK.equals(request.method())) {
                acks.add(request);
                return ackAttempts.incrementAndGet();
            }
            return inviteAttempts.incrementAndGet();
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
