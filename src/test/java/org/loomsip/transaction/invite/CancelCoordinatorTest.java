package org.loomsip.transaction.invite;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.loomsip.message.SipHeaders;
import org.loomsip.message.SipMessage;
import org.loomsip.message.SipMethod;
import org.loomsip.message.SipRequest;
import org.loomsip.message.SipResponses;
import org.loomsip.message.SipUri;
import org.loomsip.transaction.TransactionMessageSender;
import org.loomsip.transaction.SipTransactionDispatcher;
import org.loomsip.transaction.noninvite.ClientTransactionHandle;
import org.loomsip.transaction.noninvite.NonInviteClientListener;
import org.loomsip.transaction.noninvite.NonInviteServerListener;
import org.loomsip.transaction.noninvite.NonInviteTransactionConfig;
import org.loomsip.transaction.noninvite.NonInviteTransactionManager;
import org.loomsip.transaction.timer.SipTimerConfig;
import org.loomsip.transaction.timer.VirtualSipScheduler;
import org.loomsip.transport.InboundSipMessage;
import org.loomsip.transport.SendResult;
import org.loomsip.transport.TransportContext;
import org.loomsip.transport.TransportEndpoint;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;

@Timeout(10)
class CancelCoordinatorTest {

    private static final SipTimerConfig TIMERS = new SipTimerConfig(
            Duration.ofMillis(100),
            Duration.ofMillis(800),
            Duration.ofSeconds(1)
    );

    @Test
    void cancelHasIndependentTransactionAndTerminatesRelatedProceedingInvite() throws Exception {
        try (CancelRig rig = new CancelRig()) {
            InviteClientHandle invite = rig.inviteClient.sendInvite(rig.invite, rig.serverEndpoint);
            assertEquals(InviteClientState.PROCEEDING, invite.state());

            ClientTransactionHandle cancel = rig.coordinator.sendCancel(invite);

            assertEquals(SipMethod.CANCEL, cancel.key().method());
            assertEquals(List.of(200), rig.cancelClientStatuses);
            assertEquals(List.of(180, 487), rig.inviteClientStatuses);
            assertEquals(1, rig.serverCancelNotifications.get());
            assertEquals(1, rig.ackCount.get());
            assertEquals(InviteClientState.COMPLETED, invite.state());
        }
    }

    @Test
    void unmatchedCancelReceives481WithoutNotifyingInviteTu() throws Exception {
        try (CancelRig rig = new CancelRig()) {
            SipRequest cancel = InviteCancellations.createCancel(rig.invite);

            rig.nonInviteClient.sendRequest(cancel, rig.serverEndpoint);

            assertEquals(List.of(481), rig.cancelClientStatuses);
            assertEquals(0, rig.serverCancelNotifications.get());
        }
    }

    private static final class CancelRig implements AutoCloseable {

        private final TransportEndpoint clientEndpoint = endpoint(18060);
        private final TransportEndpoint serverEndpoint = endpoint(18061);
        private final SipRequest invite = invite(clientEndpoint);
        private final VirtualSipScheduler scheduler = new VirtualSipScheduler();
        private final TestNetwork network = new TestNetwork(clientEndpoint, serverEndpoint);
        private final List<Integer> inviteClientStatuses = new CopyOnWriteArrayList<>();
        private final List<Integer> cancelClientStatuses = new CopyOnWriteArrayList<>();
        private final AtomicInteger serverCancelNotifications = new AtomicInteger();
        private final AtomicInteger ackCount = new AtomicInteger();
        private final InviteTransactionManager inviteClient;
        private final InviteTransactionManager inviteServer;
        private final NonInviteTransactionManager nonInviteClient;
        private final NonInviteTransactionManager nonInviteServer;
        private final CancelCoordinator coordinator;

        private CancelRig() {
            AtomicReference<SipRequest> serverInvite = new AtomicReference<>();
            InviteClientListener clientListener = (transaction, response, context) ->
                    inviteClientStatuses.add(response.statusCode());
            InviteServerListener serverListener = new InviteServerListener() {
                @Override
                public void onInvite(
                        InviteServerHandle transaction,
                        SipRequest request,
                        TransportContext context
                ) {
                    serverInvite.set(request);
                    transaction.sendResponse(SipResponses.createResponse(request, 180, "Ringing"));
                }

                @Override
                public void onCancel(
                        InviteServerHandle transaction,
                        SipRequest cancel,
                        TransportContext context
                ) {
                    serverCancelNotifications.incrementAndGet();
                    transaction.sendResponse(SipResponses.createResponse(
                            serverInvite.get(),
                            487,
                            "Request Terminated",
                            "cancelled-invite"
                    ));
                }

                @Override
                public void onAck(
                        InviteServerHandle transaction,
                        SipRequest ack,
                        TransportContext context
                ) {
                    ackCount.incrementAndGet();
                }
            };
            InviteClientListener ignoredInviteClient = (transaction, response, context) -> {
            };
            InviteServerListener ignoredInviteServer = (transaction, request, context) -> {
            };
            inviteClient = inviteManager(clientListener, ignoredInviteServer);
            inviteServer = inviteManager(ignoredInviteClient, serverListener);

            NonInviteClientListener cancelClientListener = (transaction, response, context) ->
                    cancelClientStatuses.add(response.statusCode());
            NonInviteClientListener ignoredNonInviteClient = (transaction, response, context) -> {
            };
            AtomicReference<CancelCoordinator> coordinatorReference = new AtomicReference<>();
            NonInviteServerListener cancelServerListener = (transaction, request, context) -> {
                try {
                    coordinatorReference.get().handleInboundCancel(transaction, request, context);
                } catch (Exception exception) {
                    throw new IllegalStateException(exception);
                }
            };
            NonInviteServerListener ignoredNonInviteServer = (transaction, request, context) -> {
            };
            nonInviteClient = nonInviteManager(cancelClientListener, ignoredNonInviteServer);
            nonInviteServer = nonInviteManager(ignoredNonInviteClient, cancelServerListener);
            coordinator = new CancelCoordinator(inviteClient, nonInviteClient);
            CancelCoordinator serverCoordinator = new CancelCoordinator(inviteServer, nonInviteServer);
            coordinatorReference.set(serverCoordinator);

            SipTransactionDispatcher clientDispatcher = new SipTransactionDispatcher(
                    inviteClient,
                    nonInviteClient
            );
            SipTransactionDispatcher serverDispatcher = new SipTransactionDispatcher(
                    inviteServer,
                    nonInviteServer
            );
            network.route(clientEndpoint, clientDispatcher::onMessage);
            network.route(serverEndpoint, serverDispatcher::onMessage);
        }

        private InviteTransactionManager inviteManager(
                InviteClientListener clientListener,
                InviteServerListener serverListener
        ) {
            return new InviteTransactionManager(
                    network,
                    TIMERS,
                    InviteTransactionConfig.DEFAULT,
                    clientListener,
                    serverListener,
                    scheduler,
                    Runnable::run,
                    Runnable::run
            );
        }

        private NonInviteTransactionManager nonInviteManager(
                NonInviteClientListener clientListener,
                NonInviteServerListener serverListener
        ) {
            return new NonInviteTransactionManager(
                    network,
                    TIMERS,
                    NonInviteTransactionConfig.DEFAULT,
                    clientListener,
                    serverListener,
                    scheduler,
                    Runnable::run,
                    Runnable::run
            );
        }

        @Override
        public void close() {
            nonInviteClient.close();
            nonInviteServer.close();
            inviteClient.close();
            inviteServer.close();
            scheduler.close();
        }
    }

    private static final class TestNetwork implements TransactionMessageSender {

        private final TransportEndpoint clientEndpoint;
        private final TransportEndpoint serverEndpoint;
        private final List<Route> routes = new CopyOnWriteArrayList<>();

        private TestNetwork(TransportEndpoint clientEndpoint, TransportEndpoint serverEndpoint) {
            this.clientEndpoint = clientEndpoint;
            this.serverEndpoint = serverEndpoint;
        }

        private void route(TransportEndpoint endpoint, Consumer<InboundSipMessage> handler) {
            routes.add(new Route(endpoint, handler));
        }

        @Override
        public CompletionStage<SendResult> send(SipMessage message, TransportEndpoint target) {
            TransportEndpoint source = target.equals(serverEndpoint) ? clientEndpoint : serverEndpoint;
            Route route = routes.stream()
                    .filter(candidate -> candidate.endpoint().equals(target))
                    .findFirst()
                    .orElseThrow();
            route.handler().accept(new InboundSipMessage(
                    message,
                    new TransportContext(target.protocol(), target.address(), source.address())
            ));
            return CompletableFuture.completedFuture(new SendResult(source, target, 1));
        }
    }

    private static SipRequest invite(TransportEndpoint local) {
        String host = local.address().getAddress().getHostAddress();
        return new SipRequest(
                SipMethod.INVITE,
                SipUri.parse("sip:bob@example.com"),
                SipHeaders.builder()
                        .add("Via", "SIP/2.0/UDP " + host + ":" + local.address().getPort()
                                + ";branch=z9hG4bK-cancel-flow;rport")
                        .add("Max-Forwards", "70")
                        .add("From", "<sip:alice@example.com>;tag=client-tag")
                        .add("To", "<sip:bob@example.com>")
                        .add("Call-ID", "cancel-flow@example.com")
                        .add("CSeq", "1 INVITE")
                        .build()
        );
    }

    private static TransportEndpoint endpoint(int port) {
        return TransportEndpoint.udp(new InetSocketAddress(InetAddress.getLoopbackAddress(), port));
    }

    private record Route(TransportEndpoint endpoint, Consumer<InboundSipMessage> handler) {
    }
}
