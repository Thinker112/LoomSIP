package org.loomsip.transport.netty;

import io.netty.handler.ssl.SslContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.loomsip.codec.SipParseException;
import org.loomsip.dialog.DialogHandle;
import org.loomsip.dialog.DialogId;
import org.loomsip.dialog.DialogLifecycleListener;
import org.loomsip.dialog.DialogManager;
import org.loomsip.dialog.DialogRequestDispatcher;
import org.loomsip.dialog.DialogRequestProfile;
import org.loomsip.dialog.DialogRequestRuntime;
import org.loomsip.dialog.DialogRuntime;
import org.loomsip.dialog.DialogState;
import org.loomsip.dialog.DialogTransactionBridge;
import org.loomsip.dialog.InMemoryDialogRepository;
import org.loomsip.message.SipBody;
import org.loomsip.message.SipHeaders;
import org.loomsip.message.SipMethod;
import org.loomsip.message.SipRequest;
import org.loomsip.message.SipResponse;
import org.loomsip.message.SipResponses;
import org.loomsip.message.SipUri;
import org.loomsip.message.SipVersion;
import org.loomsip.message.header.SentBy;
import org.loomsip.message.header.SipHeaderValues;
import org.loomsip.message.header.ViaTransport;
import org.loomsip.transaction.SipTransactionDispatcher;
import org.loomsip.transaction.invite.InviteClientHandle;
import org.loomsip.transaction.invite.InviteClientListener;
import org.loomsip.transaction.invite.InviteServerHandle;
import org.loomsip.transaction.invite.InviteServerListener;
import org.loomsip.transaction.invite.InviteTransactionConfig;
import org.loomsip.transaction.invite.InviteTransactionManager;
import org.loomsip.transaction.noninvite.ClientTransactionHandle;
import org.loomsip.transaction.noninvite.NonInviteClientListener;
import org.loomsip.transaction.noninvite.NonInviteServerListener;
import org.loomsip.transaction.noninvite.NonInviteTransactionConfig;
import org.loomsip.transaction.noninvite.NonInviteTransactionManager;
import org.loomsip.transaction.noninvite.ServerTransactionHandle;
import org.loomsip.transaction.timer.SipScheduler;
import org.loomsip.transaction.timer.SipTimerConfig;
import org.loomsip.transaction.timer.VirtualSipScheduler;
import org.loomsip.transport.InboundSipMessage;
import org.loomsip.transport.SipMessageHandler;
import org.loomsip.transport.TransportContext;
import org.loomsip.transport.TransportEndpoint;
import org.loomsip.transport.TransportProtocol;
import org.loomsip.transport.TransportRegistry;
import org.loomsip.transport.TransportSelector;
import org.loomsip.transport.TransportState;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end 5F acceptance for reliable TCP and TLS transports.
 *
 * <p>The test deliberately assembles the current public components instead of
 * introducing a Stack facade before that API is stable:</p>
 *
 * <pre>{@code
 * Netty TCP/TLS transport
 *          |
 *          v
 * TransportRegistry -> SipTransactionDispatcher
 *                              |
 *                              v
 *                    Transaction/Dialog Bridge
 *                              |
 *                              v
 *                    INVITE -> ACK -> BYE call
 * }</pre>
 */
@Timeout(30)
class ReliableTransportDialogIntegrationTest {

    private static final SipTimerConfig TIMERS = new SipTimerConfig(
            Duration.ofMillis(50),
            Duration.ofMillis(400),
            Duration.ofMillis(100)
    );

    @Test
    void completesTcpInviteReInviteAndByeWithOneReusedConnection() throws Exception {
        runCall(TransportProtocol.TCP, null, null);
    }

    @Test
    void completesTlsInviteReInviteAndByeWithHandshakeAndOneReusedConnection() throws Exception {
        try (TestTlsMaterial material = TestTlsMaterial.create("localhost")) {
            runCall(
                    TransportProtocol.TLS,
                    material.serverContext(),
                    material.trustedClientContext()
            );
        }
    }

    private static void runCall(
            TransportProtocol protocol,
            SslContext serverContext,
            SslContext clientContext
    ) throws Exception {
        DelegatingHandler clientHandler = new DelegatingHandler();
        DelegatingHandler serverHandler = new DelegatingHandler();
        NettyTcpTransport clientTransport = transport(
                protocol,
                clientHandler,
                serverContext,
                clientContext
        );
        NettyTcpTransport serverTransport = transport(
                protocol,
                serverHandler,
                serverContext,
                clientContext
        );
        TransportRegistry clientRegistry = registry(protocol, clientTransport);
        TransportRegistry serverRegistry = registry(protocol, serverTransport);
        CallSignals signals = new CallSignals();
        CompletableFuture<Void> releaseInitialFinal = new CompletableFuture<>();
        AtomicInteger serverInvites = new AtomicInteger();
        AtomicInteger serverAcks = new AtomicInteger();
        AtomicReference<EndpointStack> clientReference = new AtomicReference<>();
        AtomicReference<EndpointStack> serverReference = new AtomicReference<>();

        clientRegistry.start();
        serverRegistry.start();
        EndpointStack client = null;
        EndpointStack server = null;
        try {
            TransportEndpoint clientEndpoint = clientTransport.localEndpoint();
            TransportEndpoint serverEndpoint = protocol == TransportProtocol.TLS
                    ? TransportEndpoint.tls(new InetSocketAddress(
                            "localhost",
                            serverTransport.localEndpoint().address().getPort()
                    ))
                    : serverTransport.localEndpoint();
            client = endpoint(
                    clientTransport,
                    clientRegistry,
                    serverEndpoint,
                    new InviteClientListener() {
                        @Override
                        public void onResponse(
                                InviteClientHandle transaction,
                                SipResponse response,
                                TransportContext context
                        ) {
                            if (response.statusCode() < 200) {
                                signals.provisional.complete(response);
                            } else {
                                signals.inviteResponses.add(response);
                            }
                        }

                        @Override
                        public void onTransportFailure(InviteClientHandle transaction, Throwable cause) {
                            signals.failure.complete(cause);
                        }

                        @Override
                        public void onLayerError(Throwable cause) {
                            signals.failure.complete(cause);
                        }
                    },
                    (transaction, request, context) -> {
                    },
                    new NonInviteClientListener() {
                        @Override
                        public void onResponse(
                                ClientTransactionHandle transaction,
                                SipResponse response,
                                TransportContext context
                        ) {
                            signals.byeResponse.complete(response);
                        }

                        @Override
                        public void onTransportFailure(ClientTransactionHandle transaction, Throwable cause) {
                            signals.failure.complete(cause);
                        }

                        @Override
                        public void onLayerError(Throwable cause) {
                            signals.failure.complete(cause);
                        }
                    },
                    (transaction, request, context) -> {
                    },
                    signals,
                    clientReference,
                    new AtomicInteger()
            );
            server = endpoint(
                    serverTransport,
                    serverRegistry,
                    clientEndpoint,
                    (transaction, response, context) -> {
                    },
                    new InviteServerListener() {
                        @Override
                        public void onInvite(
                                InviteServerHandle transaction,
                                SipRequest request,
                                TransportContext context
                        ) {
                            int attempt = serverInvites.incrementAndGet();
                            transaction.sendResponse(
                                    SipResponses.createResponse(request, 180, "Ringing", "server-tag")
                            );
                            if (attempt == 1) {
                                releaseInitialFinal.thenRun(() -> transaction.sendResponse(
                                        success(request, "server-tag", contact(
                                                "bob", serverTransport.localEndpoint(), protocol
                                        ))
                                ));
                            } else {
                                transaction.sendResponse(
                                        success(request, "server-tag", contact(
                                                "bob-refreshed", serverTransport.localEndpoint(), protocol
                                        ))
                                );
                            }
                        }

                        @Override
                        public void onTransportFailure(InviteServerHandle transaction, Throwable cause) {
                            signals.failure.complete(cause);
                        }

                        @Override
                        public void onLayerError(Throwable cause) {
                            signals.failure.complete(cause);
                        }
                    },
                    (transaction, response, context) -> {
                    },
                    new NonInviteServerListener() {
                        @Override
                        public void onRequest(
                                ServerTransactionHandle transaction,
                                SipRequest request,
                                TransportContext context
                        ) {
                            transaction.sendResponse(SipResponses.createResponse(
                                    request,
                                    200,
                                    "OK",
                                    "server-tag"
                            ));
                        }

                        @Override
                        public void onTransportFailure(ServerTransactionHandle transaction, Throwable cause) {
                            signals.failure.complete(cause);
                        }

                        @Override
                        public void onLayerError(Throwable cause) {
                            signals.failure.complete(cause);
                        }
                    },
                    signals,
                    serverReference,
                    serverAcks
            );
            clientReference.set(client);
            serverReference.set(server);
            clientHandler.delegateTo(client.dispatcher);
            serverHandler.delegateTo(server.dispatcher);

            SipRequest initialInvite = initialInvite(clientEndpoint, protocol);
            InviteClientHandle initialTransaction = client.inviteTransactions.sendInvite(
                    initialInvite,
                    serverEndpoint
            );

            assertEquals(180, awaitSignal(signals.provisional, signals.failure).statusCode());
            assertEquals(1, serverInvites.get());
            client.scheduler.advanceBy(TIMERS.t1().multipliedBy(4));
            assertEquals(1, serverInvites.get(), "reliable TCP/TLS must not run Timer A");
            assertEquals(TransportState.RUNNING, clientTransport.state());
            assertEquals(TransportState.RUNNING, serverTransport.state());
            releaseInitialFinal.complete(null);

            SipResponse initialSuccess = awaitInviteResponse(signals);
            assertEquals(200, initialSuccess.statusCode());
            assertEquals(1, clientTransport.connectionManager().activeConnectionCount());
            assertEquals(1, serverTransport.connectionManager().activeConnectionCount());

            DialogId clientId = new DialogId("basic-call@example.com", "client-tag", "server-tag");
            DialogId serverId = new DialogId("basic-call@example.com", "server-tag", "client-tag");
            DialogHandle clientDialog = awaitDialog(client.dialogs, clientId);
            DialogHandle serverDialog = awaitDialog(server.dialogs, serverId);
            assertEquals(DialogState.CONFIRMED, clientDialog.snapshot().state());
            assertEquals(DialogState.CONFIRMED, serverDialog.snapshot().state());

            InviteClientHandle reinvite = clientDialog.sendReInvite(
                    SipHeaders.builder()
                            .add("Contact", contact(
                                    "alice-refreshed", clientEndpoint, protocol
                            ))
                            .build(),
                    SipBody.empty()
            ).toCompletableFuture().get(5, TimeUnit.SECONDS);
            SipResponse refreshedSuccess = awaitInviteResponse(signals);
            assertEquals(200, refreshedSuccess.statusCode());
            assertEquals(2, serverInvites.get());
            assertEquals(2, clientDialog.snapshot().localCSeq());
            assertEquals(2, serverDialog.snapshot().remoteCSeq());
            assertEquals(
                    SipUri.parse(contactUri("bob-refreshed", serverEndpoint, protocol)),
                    clientDialog.snapshot().remoteTarget().orElseThrow()
            );
            assertEquals(
                    SipUri.parse(contactUri("alice-refreshed", clientEndpoint, protocol)),
                    serverDialog.snapshot().remoteTarget().orElseThrow()
            );
            assertEquals(1, clientTransport.connectionManager().activeConnectionCount());
            assertEquals(1, serverTransport.connectionManager().activeConnectionCount());

            ClientTransactionHandle bye = clientDialog.sendBye()
                    .toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertEquals(200, signals.byeResponse.get(5, TimeUnit.SECONDS).statusCode());
            clientDialog.terminated().toCompletableFuture().get(5, TimeUnit.SECONDS);
            serverDialog.terminated().toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertEquals(2, serverAcks.get(), "2xx ACKs must be routed through Dialog");
            assertEquals(0, client.dialogs.activeDialogs());
            assertEquals(0, server.dialogs.activeDialogs());
            assertFalse(signals.failure.isDone(), () -> "unexpected layer failure: " + signals.failure.join());

            client.scheduler.advanceBy(TIMERS.sixtyFourT1());
            server.scheduler.advanceBy(TIMERS.sixtyFourT1());
            initialTransaction.terminated().toCompletableFuture().get(5, TimeUnit.SECONDS);
            reinvite.terminated().toCompletableFuture().get(5, TimeUnit.SECONDS);
            bye.terminated().toCompletableFuture().get(5, TimeUnit.SECONDS);
            EndpointStack clientStack = client;
            EndpointStack serverStack = server;
            awaitZero(() -> clientStack.inviteTransactions.activeClientTransactions());
            awaitZero(() -> serverStack.inviteTransactions.activeServerTransactions());
            awaitZero(() -> clientStack.nonInviteTransactions.activeClientTransactions());
            awaitZero(() -> serverStack.nonInviteTransactions.activeServerTransactions());
        } finally {
            if (client != null) {
                client.close();
            }
            if (server != null) {
                server.close();
            }
            clientRegistry.close();
            serverRegistry.close();
        }
    }

    private static EndpointStack endpoint(
            NettyTcpTransport transport,
            TransportRegistry registry,
            TransportEndpoint remote,
            InviteClientListener inviteClient,
            InviteServerListener inviteServer,
            NonInviteClientListener nonInviteClient,
            NonInviteServerListener nonInviteServer,
            CallSignals signals,
            AtomicReference<EndpointStack> reference,
            AtomicInteger acknowledgedInvites
    ) {
        VirtualSipScheduler scheduler = new VirtualSipScheduler();
        ExecutorService executor = Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name("loomsip-5f-test-", 0).factory()
        );
        TransportProtocol protocol = transport.localEndpoint().protocol();
        TransportSelector selector = new TransportSelector(registry);
        org.loomsip.transaction.ConnectionAwareMessageSender sender =
                new org.loomsip.transaction.ConnectionAwareMessageSender(selector);
        AtomicInteger branches = new AtomicInteger();
        DialogRuntime dialogRuntime = new DialogRuntime(
                sender,
                (uri, preferred) -> CompletableFuture.completedFuture(remote),
                scheduler,
                TIMERS,
                () -> "z9hG4bK-5f-call-" + branches.incrementAndGet(),
                executor
        );
        DialogRequestDispatcher requestDispatcher = new DialogRequestDispatcher() {
            @Override
            public InviteClientHandle sendInvite(SipRequest request, TransportEndpoint target)
                    throws org.loomsip.transaction.TransactionKeyException {
                return reference.get().inviteTransactions.sendInvite(request, target);
            }

            @Override
            public ClientTransactionHandle sendNonInvite(SipRequest request, TransportEndpoint target)
                    throws org.loomsip.transaction.TransactionKeyException {
                return reference.get().nonInviteTransactions.sendRequest(request, target);
            }
        };
        SentBy sentBy = new SentBy(
                transport.localEndpoint().address().getAddress().getHostAddress(),
                transport.localEndpoint().address().getPort()
        );
        DialogRequestRuntime requestRuntime = new DialogRequestRuntime(
                dialogRuntime,
                new DialogRequestProfile(
                        switch (protocol) {
                            case TCP -> ViaTransport.TCP;
                            case TLS -> ViaTransport.TLS;
                            default -> throw new IllegalArgumentException("5F requires reliable transport");
                        },
                        sentBy,
                        protocol,
                        false
                ),
                requestDispatcher
        );
        DialogLifecycleListener lifecycle = new DialogLifecycleListener() {
            @Override
            public void onAckReceived(DialogHandle dialog, SipRequest ack, TransportContext context) {
                acknowledgedInvites.incrementAndGet();
            }

            @Override
            public void onFailure(DialogHandle dialog, Throwable cause) {
                signals.failure.complete(cause);
            }

            @Override
            public void onManagerFailure(Throwable cause) {
                signals.failure.complete(cause);
            }
        };
        DialogManager dialogs = new DialogManager(
                new org.loomsip.dialog.DialogConfig(8, 128, 64),
                lifecycle,
                new InMemoryDialogRepository(8),
                executor,
                executor,
                requestRuntime
        );
        DialogTransactionBridge bridge = new DialogTransactionBridge(
                dialogs,
                inviteClient,
                inviteServer,
                nonInviteClient,
                nonInviteServer
        );
        InviteTransactionManager inviteTransactions = new InviteTransactionManager(
                sender,
                TIMERS,
                InviteTransactionConfig.DEFAULT,
                bridge.clientListener(),
                bridge.serverListener(),
                scheduler,
                executor,
                executor
        );
        NonInviteTransactionManager nonInviteTransactions = new NonInviteTransactionManager(
                sender,
                TIMERS,
                NonInviteTransactionConfig.DEFAULT,
                bridge.nonInviteClientListener(),
                bridge.nonInviteServerListener(),
                scheduler,
                executor,
                executor
        );
        return new EndpointStack(
                dialogs,
                inviteTransactions,
                nonInviteTransactions,
                new SipTransactionDispatcher(inviteTransactions, nonInviteTransactions),
                scheduler,
                executor
        );
    }

    private static TransportRegistry registry(TransportProtocol protocol, NettyTcpTransport transport) {
        TransportRegistry registry = new TransportRegistry();
        registry.register(protocol, transport);
        return registry;
    }

    private static NettyTcpTransport transport(
            TransportProtocol protocol,
            SipMessageHandler handler,
            SslContext serverContext,
            SslContext clientContext
    ) {
        if (protocol == TransportProtocol.TCP) {
            return new NettyTcpTransport(
                    new TcpTransportConfig(loopbackAddress()),
                    handler
            );
        }
        return new NettyTlsTransport(
                new TlsTransportConfig(
                        loopbackAddress(),
                        org.loomsip.codec.StreamBufferLimits.DEFAULT,
                        org.loomsip.transport.ConnectionLimits.DEFAULT,
                        org.loomsip.transport.WriteQueueLimits.DEFAULT,
                        serverContext,
                        clientContext,
                        Duration.ofSeconds(5),
                        true,
                        "5f-test-profile",
                        java.util.List.of(),
                        java.util.List.of()
                ),
                handler
        );
    }

    private static SipRequest initialInvite(TransportEndpoint local, TransportProtocol protocol) {
        return new SipRequest(
                SipMethod.INVITE,
                SipUri.parse(protocol == TransportProtocol.TLS
                        ? "sips:bob@example.com"
                        : "sip:bob@example.com"),
                SipVersion.SIP_2_0,
                SipHeaders.builder()
                        .add("Via", "SIP/2.0/" + protocol + " " + sentBy(local)
                                + ";branch=z9hG4bK-5f-initial")
                        .add("Max-Forwards", "70")
                        .add("From", "<sip:alice@example.com>;tag=client-tag")
                        .add("To", "<sip:bob@example.com>")
                        .add("Call-ID", "basic-call@example.com")
                        .add("CSeq", "1 INVITE")
                        .add("Contact", contact("alice", local, protocol))
                        .build(),
                SipBody.empty()
        );
    }

    private static SipResponse success(SipRequest request, String tag, String contact) {
        SipResponse base = SipResponses.createResponse(request, 200, "OK", tag);
        return new SipResponse(
                base.version(),
                base.statusCode(),
                base.reasonPhrase(),
                base.headers().toBuilder().add("Contact", contact).build(),
                base.body()
        );
    }

    private static String contact(String user, TransportEndpoint endpoint, TransportProtocol protocol) {
        return "<" + contactUri(user, endpoint, protocol) + ">";
    }

    private static String contactUri(String user, TransportEndpoint endpoint, TransportProtocol protocol) {
        String scheme = protocol == TransportProtocol.TLS ? "sips" : "sip";
        return scheme + ":" + user + "@" + sentBy(endpoint);
    }

    private static String sentBy(TransportEndpoint endpoint) {
        return endpoint.address().getAddress().getHostAddress() + ":" + endpoint.address().getPort();
    }

    private static DialogHandle awaitDialog(DialogManager dialogs, DialogId id) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            java.util.Optional<DialogHandle> dialog = dialogs.find(id);
            if (dialog.isPresent()) {
                return dialog.orElseThrow();
            }
            Thread.yield();
        }
        throw new AssertionError("Dialog was not created: " + id);
    }

    private static SipResponse awaitInviteResponse(CallSignals signals) throws Exception {
        while (true) {
            if (signals.failure.isDone()) {
                throw new AssertionError("transport or protocol failure", signals.failure.join());
            }
            SipResponse response = signals.inviteResponses.poll(100, TimeUnit.MILLISECONDS);
            if (response != null) {
                return response;
            }
        }
    }

    private static SipResponse awaitSignal(
            CompletableFuture<SipResponse> signal,
            CompletableFuture<Throwable> failure
    ) throws Exception {
        while (true) {
            if (failure.isDone()) {
                throw new AssertionError("transport or protocol failure", failure.join());
            }
            try {
                return signal.get(100, TimeUnit.MILLISECONDS);
            } catch (java.util.concurrent.TimeoutException ignored) {
                // Keep checking the failure channel so handshake errors remain actionable.
            }
        }
    }

    private static void awaitZero(java.util.function.IntSupplier count) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (count.getAsInt() != 0 && System.nanoTime() < deadline) {
            Thread.yield();
        }
        assertEquals(0, count.getAsInt());
    }

    private static InetSocketAddress loopbackAddress() {
        return new InetSocketAddress(InetAddress.getLoopbackAddress(), 0);
    }

    private static final class CallSignals {
        private final java.util.concurrent.BlockingQueue<SipResponse> inviteResponses =
                new java.util.concurrent.LinkedBlockingQueue<>();
        private final CompletableFuture<SipResponse> provisional = new CompletableFuture<>();
        private final CompletableFuture<SipResponse> byeResponse = new CompletableFuture<>();
        private final CompletableFuture<Throwable> failure = new CompletableFuture<>();
    }

    private static final class DelegatingHandler implements SipMessageHandler {

        private volatile SipMessageHandler delegate;

        private void delegateTo(SipMessageHandler delegate) {
            this.delegate = Objects.requireNonNull(delegate, "delegate");
        }

        @Override
        public void onMessage(InboundSipMessage message) {
            Objects.requireNonNull(delegate, "handler delegate").onMessage(message);
        }

        @Override
        public void onMalformedMessage(TransportContext context, SipParseException cause) {
            Objects.requireNonNull(delegate, "handler delegate").onMalformedMessage(context, cause);
        }

        @Override
        public void onTransportError(Throwable cause) {
            if (delegate != null) {
                delegate.onTransportError(cause);
            }
        }
    }

    private static final class EndpointStack implements AutoCloseable {

        private final DialogManager dialogs;
        private final InviteTransactionManager inviteTransactions;
        private final NonInviteTransactionManager nonInviteTransactions;
        private final SipTransactionDispatcher dispatcher;
        private final VirtualSipScheduler scheduler;
        private final ExecutorService executor;

        private EndpointStack(
                DialogManager dialogs,
                InviteTransactionManager inviteTransactions,
                NonInviteTransactionManager nonInviteTransactions,
                SipTransactionDispatcher dispatcher,
                VirtualSipScheduler scheduler,
                ExecutorService executor
        ) {
            this.dialogs = dialogs;
            this.inviteTransactions = inviteTransactions;
            this.nonInviteTransactions = nonInviteTransactions;
            this.dispatcher = dispatcher;
            this.scheduler = scheduler;
            this.executor = executor;
        }

        @Override
        public void close() {
            dialogs.close();
            inviteTransactions.close();
            nonInviteTransactions.close();
            scheduler.close();
            executor.shutdownNow();
        }
    }
}
