package org.loomsip.dialog;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.loomsip.codec.SipParseException;
import org.loomsip.message.SipBody;
import org.loomsip.message.SipHeaders;
import org.loomsip.message.SipMethod;
import org.loomsip.message.SipRequest;
import org.loomsip.message.SipResponse;
import org.loomsip.message.SipResponses;
import org.loomsip.message.SipUri;
import org.loomsip.message.header.SentBy;
import org.loomsip.message.header.SipHeaderValues;
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
import org.loomsip.transaction.timer.DefaultSipScheduler;
import org.loomsip.transaction.timer.SipTimerConfig;
import org.loomsip.transport.InboundSipMessage;
import org.loomsip.transport.SipMessageHandler;
import org.loomsip.transport.TransportContext;
import org.loomsip.transport.TransportEndpoint;
import org.loomsip.transport.netty.NettyUdpTransport;
import org.loomsip.transport.netty.UdpTransportConfig;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Timeout(20)
class DialogBasicCallUdpIntegrationTest {

    @Test
    void completesInviteReInviteAndByeAcrossNettyUdp() throws Exception {
        DelegatingHandler clientHandler = new DelegatingHandler();
        DelegatingHandler serverHandler = new DelegatingHandler();
        NettyUdpTransport clientTransport = transport(clientHandler);
        NettyUdpTransport serverTransport = transport(serverHandler);
        clientTransport.start();
        serverTransport.start();

        BlockingQueue<SipResponse> clientInviteResponses = new LinkedBlockingQueue<>();
        CompletableFuture<SipResponse> clientByeResponse = new CompletableFuture<>();
        CompletableFuture<Throwable> failure = new CompletableFuture<>();
        AtomicInteger serverInvites = new AtomicInteger();
        AtomicInteger serverAcks = new AtomicInteger();
        AtomicReference<EndpointStack> clientReference = new AtomicReference<>();
        AtomicReference<EndpointStack> serverReference = new AtomicReference<>();

        InviteClientListener clientInviteApplication = new InviteClientListener() {
            @Override
            public void onResponse(
                    InviteClientHandle transaction,
                    SipResponse response,
                    TransportContext context
            ) {
                if (response.statusCode() >= 200) {
                    clientInviteResponses.add(response);
                }
            }

            @Override
            public void onLayerError(Throwable cause) {
                failure.complete(cause);
            }
        };
        InviteServerListener serverInviteApplication = new InviteServerListener() {
            @Override
            public void onInvite(
                    InviteServerHandle transaction,
                    SipRequest request,
                    TransportContext context
            ) {
                int attempt = serverInvites.incrementAndGet();
                String contact = attempt == 1
                        ? contact("bob", serverTransport.localEndpoint())
                        : contact("bob-refreshed", serverTransport.localEndpoint());
                transaction.sendResponse(success(request, "server-tag", contact));
            }

            @Override
            public void onLayerError(Throwable cause) {
                failure.complete(cause);
            }
        };
        NonInviteClientListener clientNonInviteApplication = new NonInviteClientListener() {
            @Override
            public void onResponse(
                    ClientTransactionHandle transaction,
                    SipResponse response,
                    TransportContext context
            ) {
                clientByeResponse.complete(response);
            }

            @Override
            public void onLayerError(Throwable cause) {
                failure.complete(cause);
            }
        };
        NonInviteServerListener serverNonInviteApplication = new NonInviteServerListener() {
            @Override
            public void onRequest(
                    ServerTransactionHandle transaction,
                    SipRequest request,
                    TransportContext context
            ) {
                transaction.sendResponse(SipResponses.createResponse(request, 200, "OK", "server-tag"));
            }

            @Override
            public void onLayerError(Throwable cause) {
                failure.complete(cause);
            }
        };

        EndpointStack client = null;
        EndpointStack server = null;
        try {
            client = endpoint(
                    clientTransport,
                    serverTransport.localEndpoint(),
                    clientInviteApplication,
                    (transaction, request, context) -> {
                    },
                    clientNonInviteApplication,
                    (transaction, request, context) -> {
                    },
                    failure,
                    clientReference,
                    new AtomicInteger()
            );
            server = endpoint(
                    serverTransport,
                    clientTransport.localEndpoint(),
                    (transaction, response, context) -> {
                    },
                    serverInviteApplication,
                    (transaction, response, context) -> {
                    },
                    serverNonInviteApplication,
                    failure,
                    serverReference,
                    serverAcks
            );
            clientReference.set(client);
            serverReference.set(server);
            clientHandler.delegateTo(client.dispatcher);
            serverHandler.delegateTo(server.dispatcher);

            SipRequest initialInvite = initialInvite(clientTransport.localEndpoint());
            client.inviteTransactions.sendInvite(initialInvite, serverTransport.localEndpoint());
            SipResponse initialSuccess = clientInviteResponses.poll(5, TimeUnit.SECONDS);
            assertEquals(200, Objects.requireNonNull(initialSuccess).statusCode());

            DialogId clientId = new DialogId("basic-call@example.com", "client-tag", "server-tag");
            DialogId serverId = new DialogId("basic-call@example.com", "server-tag", "client-tag");
            DialogHandle clientDialog = awaitDialog(client.dialogs, clientId);
            DialogHandle serverDialog = awaitDialog(server.dialogs, serverId);

            await(clientDialog.sendReInvite(
                    SipHeaders.builder()
                            .add("Contact", contact("alice-refreshed", clientTransport.localEndpoint()))
                            .build(),
                    SipBody.empty()
            ));
            SipResponse refreshedSuccess = clientInviteResponses.poll(5, TimeUnit.SECONDS);
            assertEquals(200, Objects.requireNonNull(refreshedSuccess).statusCode());
            assertEquals(2, serverInvites.get());
            assertEquals(2, clientDialog.snapshot().localCSeq());
            assertEquals(2, serverDialog.snapshot().remoteCSeq());
            assertEquals(
                    SipUri.parse(contactUri("bob-refreshed", serverTransport.localEndpoint())),
                    clientDialog.snapshot().remoteTarget().orElseThrow()
            );
            assertEquals(
                    SipUri.parse(contactUri("alice-refreshed", clientTransport.localEndpoint())),
                    serverDialog.snapshot().remoteTarget().orElseThrow()
            );

            await(clientDialog.sendBye());
            SipResponse byeSuccess = clientByeResponse.get(5, TimeUnit.SECONDS);
            assertEquals(200, byeSuccess.statusCode());
            await(clientDialog.terminated());
            await(serverDialog.terminated());

            assertTrue(client.dialogs.find(clientId).isEmpty());
            assertTrue(server.dialogs.find(serverId).isEmpty());
            assertEquals(2, serverAcks.get());
            assertFalse(failure.isDone(), () -> "unexpected layer failure: " + failure.join());
        } finally {
            if (client != null) {
                client.close();
            }
            if (server != null) {
                server.close();
            }
            clientTransport.close();
            serverTransport.close();
        }
    }

    private static EndpointStack endpoint(
            NettyUdpTransport transport,
            TransportEndpoint remote,
            InviteClientListener inviteClient,
            InviteServerListener inviteServer,
            NonInviteClientListener nonInviteClient,
            NonInviteServerListener nonInviteServer,
            CompletableFuture<Throwable> failure,
            AtomicReference<EndpointStack> reference,
            AtomicInteger acknowledgedInvites
    ) {
        DefaultSipScheduler dialogScheduler = new DefaultSipScheduler();
        AtomicInteger branches = new AtomicInteger();
        DialogRuntime dialogRuntime = new DialogRuntime(
                transport::send,
                (uri, protocol) -> CompletableFuture.completedFuture(remote),
                dialogScheduler,
                SipTimerConfig.DEFAULT,
                () -> "z9hG4bK-dialog-call-" + branches.incrementAndGet()
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
        DialogRequestRuntime requestRuntime = new DialogRequestRuntime(
                dialogRuntime,
                DialogRequestProfile.udp(new SentBy(
                        transport.localEndpoint().address().getAddress().getHostAddress(),
                        transport.localEndpoint().address().getPort()
                )),
                requestDispatcher
        );
        DialogLifecycleListener lifecycle = new DialogLifecycleListener() {
            @Override
            public void onAckReceived(DialogHandle dialog, SipRequest ack, TransportContext context) {
                acknowledgedInvites.incrementAndGet();
            }

            @Override
            public void onFailure(DialogHandle dialog, Throwable cause) {
                failure.complete(cause);
            }

            @Override
            public void onManagerFailure(Throwable cause) {
                failure.complete(cause);
            }
        };
        DialogManager dialogs = new DialogManager(new DialogConfig(8, 128, 64), lifecycle, requestRuntime);
        DialogTransactionBridge bridge = new DialogTransactionBridge(
                dialogs,
                inviteClient,
                inviteServer,
                nonInviteClient,
                nonInviteServer
        );
        InviteTransactionManager inviteTransactions = new InviteTransactionManager(
                transport::send,
                SipTimerConfig.DEFAULT,
                InviteTransactionConfig.DEFAULT,
                bridge.clientListener(),
                bridge.serverListener()
        );
        NonInviteTransactionManager nonInviteTransactions = new NonInviteTransactionManager(
                transport::send,
                SipTimerConfig.DEFAULT,
                NonInviteTransactionConfig.DEFAULT,
                bridge.nonInviteClientListener(),
                bridge.nonInviteServerListener()
        );
        return new EndpointStack(
                dialogs,
                inviteTransactions,
                nonInviteTransactions,
                new SipTransactionDispatcher(inviteTransactions, nonInviteTransactions),
                dialogScheduler
        );
    }

    private static SipRequest initialInvite(TransportEndpoint local) {
        String host = local.address().getAddress().getHostAddress();
        int port = local.address().getPort();
        return new SipRequest(
                SipMethod.INVITE,
                SipUri.parse("sip:bob@example.com"),
                SipHeaders.builder()
                        .add("Via", "SIP/2.0/UDP " + host + ":" + port
                                + ";branch=z9hG4bK-basic-call;rport")
                        .add("Max-Forwards", "70")
                        .add("From", "<sip:alice@example.com>;tag=client-tag")
                        .add("To", "<sip:bob@example.com>")
                        .add("Call-ID", "basic-call@example.com")
                        .add("CSeq", "1 INVITE")
                        .add("Contact", contact("alice", local))
                        .build()
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

    private static String contact(String user, TransportEndpoint endpoint) {
        return "<" + contactUri(user, endpoint) + ">";
    }

    private static String contactUri(String user, TransportEndpoint endpoint) {
        String host = endpoint.address().getAddress().getHostAddress();
        return "sip:" + user + "@" + host + ":" + endpoint.address().getPort();
    }

    private static DialogHandle awaitDialog(DialogManager dialogs, DialogId id) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            java.util.Optional<DialogHandle> dialog = dialogs.find(id);
            if (dialog.isPresent()) {
                return dialog.orElseThrow();
            }
            Thread.sleep(5);
        }
        throw new AssertionError("Dialog was not created: " + id);
    }

    private static <T> T await(java.util.concurrent.CompletionStage<T> stage) {
        return stage.toCompletableFuture().join();
    }

    private static NettyUdpTransport transport(SipMessageHandler handler) {
        return new NettyUdpTransport(
                new UdpTransportConfig(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0)),
                handler
        );
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
            Objects.requireNonNull(delegate, "handler delegate").onTransportError(cause);
        }
    }

    private static final class EndpointStack implements AutoCloseable {

        private final DialogManager dialogs;
        private final InviteTransactionManager inviteTransactions;
        private final NonInviteTransactionManager nonInviteTransactions;
        private final SipTransactionDispatcher dispatcher;
        private final DefaultSipScheduler dialogScheduler;
        private EndpointStack(
                DialogManager dialogs,
                InviteTransactionManager inviteTransactions,
                NonInviteTransactionManager nonInviteTransactions,
                SipTransactionDispatcher dispatcher,
                DefaultSipScheduler dialogScheduler
        ) {
            this.dialogs = dialogs;
            this.inviteTransactions = inviteTransactions;
            this.nonInviteTransactions = nonInviteTransactions;
            this.dispatcher = dispatcher;
            this.dialogScheduler = dialogScheduler;
        }

        @Override
        public void close() {
            dialogs.close();
            inviteTransactions.close();
            nonInviteTransactions.close();
            dialogScheduler.close();
        }
    }
}
