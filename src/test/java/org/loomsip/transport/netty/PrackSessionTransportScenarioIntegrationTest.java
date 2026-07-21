package org.loomsip.transport.netty;

import io.netty.handler.ssl.SslContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.loomsip.dialog.DialogId;
import org.loomsip.dialog.DialogTerminationReason;
import org.loomsip.dialog.ReliableProvisionalConfig;
import org.loomsip.dialog.ReliableProvisionalManager;
import org.loomsip.message.SipBody;
import org.loomsip.message.SipHeaders;
import org.loomsip.message.SipMethod;
import org.loomsip.message.SipRequest;
import org.loomsip.message.SipResponse;
import org.loomsip.message.SipResponses;
import org.loomsip.message.SipUri;
import org.loomsip.message.SipVersion;
import org.loomsip.message.header.SipHeaderValues;
import org.loomsip.testkit.ScenarioEndpoint;
import org.loomsip.testkit.ScenarioInboundHandler;
import org.loomsip.transaction.ConnectionAwareMessageSender;
import org.loomsip.transaction.invite.InviteServerHandle;
import org.loomsip.transaction.invite.InviteServerListener;
import org.loomsip.transaction.noninvite.NonInviteClientListener;
import org.loomsip.transaction.noninvite.NonInviteServerListener;
import org.loomsip.transaction.noninvite.ServerTransactionHandle;
import org.loomsip.transaction.timer.SipTimerConfig;
import org.loomsip.transaction.timer.VirtualSipScheduler;
import org.loomsip.transport.SipTransport;
import org.loomsip.transport.TransportContext;
import org.loomsip.transport.TransportEndpoint;
import org.loomsip.transport.TransportProtocol;
import org.loomsip.transport.TransportRegistry;
import org.loomsip.transport.TransportSelector;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.List;
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

/**
 * 6G-D real-transport acceptance for RFC 3262 and RFC 4028 happy paths.
 *
 * <pre>{@code
 * INVITE --> 183 Require: 100rel --> automatic PRACK --> 200 PRACK
 *                                                        |
 *                                                        v
 *                                    200 INVITE + Session-Expires
 *                                                        |
 *                                         virtual half-interval
 *                                                        |
 *                                                        v
 *                                             UPDATE --> 200 UPDATE
 * }</pre>
 *
 * <p>Each protocol runs the same successful call path. The Session Timer is
 * advanced through the test-owned virtual scheduler, so the acceptance test
 * verifies timer-triggered UPDATE construction without sleeping.</p>
 */
@Timeout(30)
class PrackSessionTransportScenarioIntegrationTest {

    @Test
    void completesPrackAndSessionRefreshOverUdp() throws Exception {
        runScenario(TransportProtocol.UDP, null, null);
    }

    @Test
    void completesPrackAndSessionRefreshOverTcp() throws Exception {
        runScenario(TransportProtocol.TCP, null, null);
    }

    @Test
    void completesPrackAndSessionRefreshOverTls() throws Exception {
        try (TestTlsMaterial material = TestTlsMaterial.create("localhost")) {
            runScenario(TransportProtocol.TLS, material.serverContext(), material.trustedClientContext());
        }
    }

    @Test
    void retransmitsReliableProvisionalOverUdpWhenPrackIsLost() throws Exception {
        ScenarioInboundHandler clientInbound = new ScenarioInboundHandler();
        ScenarioInboundHandler serverInbound = new ScenarioInboundHandler();
        SipTransport clientTransport = transport(TransportProtocol.UDP, clientInbound, null, null);
        SipTransport serverTransport = transport(TransportProtocol.UDP, serverInbound, null, null);
        TransportRegistry clientRegistry = registry(TransportProtocol.UDP, clientTransport);
        TransportRegistry serverRegistry = registry(TransportProtocol.UDP, serverTransport);
        VirtualSipScheduler reliableScheduler = new VirtualSipScheduler();
        ReliableProvisionalManager serverReliable = reliableManager(reliableScheduler);
        ScenarioEndpoint client = null;
        ScenarioEndpoint server = null;
        try {
            clientRegistry.start();
            serverRegistry.start();
            CompletableFuture<Throwable> failure = new CompletableFuture<>();
            BlockingQueue<SipResponse> provisionals = new LinkedBlockingQueue<>();
            TransportEndpoint serverEndpoint = serverTransport.localEndpoint();
            TransportEndpoint serverRemote = clientTransport.localEndpoint();
            client = ScenarioEndpoint.create(
                    clientTransport,
                    new ConnectionAwareMessageSender(new TransportSelector(clientRegistry)),
                    serverEndpoint,
                    SipTimerConfig.DEFAULT,
                    lifecycle(failure),
                    (transaction, response, context) -> {
                        if (response.statusCode() == 183) {
                            provisionals.add(response);
                        }
                    },
                    (transaction, request, context) -> { },
                    nonInviteClient(failure, new CompletableFuture<>()),
                    (transaction, request, context) -> { },
                    null,
                    null
            );
            server = ScenarioEndpoint.create(
                    serverTransport,
                    new ConnectionAwareMessageSender(new TransportSelector(serverRegistry)),
                    serverRemote,
                    SipTimerConfig.DEFAULT,
                    lifecycle(failure),
                    (transaction, response, context) -> { },
                    (transaction, request, context) -> transaction.sendResponse(provisional(request, serverEndpoint)),
                    nonInviteClient(failure, new CompletableFuture<>()),
                    (transaction, request, context) -> { },
                    serverReliable,
                    null
            );
            clientInbound.bind(client.dispatcher());
            serverInbound.bind(server.dispatcher());

            client.inviteTransactions().sendInvite(invite(clientTransport.localEndpoint(), TransportProtocol.UDP), serverEndpoint);
            SipResponse first = Objects.requireNonNull(provisionals.poll(5, TimeUnit.SECONDS));
            reliableScheduler.advanceBy(SipTimerConfig.DEFAULT.t1());
            SipResponse retransmission = Objects.requireNonNull(provisionals.poll(5, TimeUnit.SECONDS));
            assertEquals(first, retransmission);
            assertEquals("1", retransmission.headers().firstValue("RSeq").orElseThrow());
            assertFalse(failure.isDone(), () -> "unexpected scenario failure: " + failure.join());
        } finally {
            if (client != null) {
                client.close();
            }
            if (server != null) {
                server.close();
            }
            serverReliable.close();
            reliableScheduler.close();
            clientRegistry.close();
            serverRegistry.close();
        }
    }

    @Test
    void expiresRemoteRefreshedDialogOverUdp() throws Exception {
        ScenarioInboundHandler clientInbound = new ScenarioInboundHandler();
        ScenarioInboundHandler serverInbound = new ScenarioInboundHandler();
        SipTransport clientTransport = transport(TransportProtocol.UDP, clientInbound, null, null);
        SipTransport serverTransport = transport(TransportProtocol.UDP, serverInbound, null, null);
        TransportRegistry clientRegistry = registry(TransportProtocol.UDP, clientTransport);
        TransportRegistry serverRegistry = registry(TransportProtocol.UDP, serverTransport);
        ScenarioEndpoint client = null;
        ScenarioEndpoint server = null;
        try {
            clientRegistry.start();
            serverRegistry.start();
            CompletableFuture<Throwable> failure = new CompletableFuture<>();
            CompletableFuture<SipResponse> success = new CompletableFuture<>();
            CompletableFuture<DialogTerminationReason> terminated = new CompletableFuture<>();
            TransportEndpoint clientEndpoint = clientTransport.localEndpoint();
            TransportEndpoint serverEndpoint = serverTransport.localEndpoint();
            client = ScenarioEndpoint.create(
                    clientTransport,
                    new ConnectionAwareMessageSender(new TransportSelector(clientRegistry)),
                    serverEndpoint,
                    SipTimerConfig.DEFAULT,
                    new org.loomsip.dialog.DialogLifecycleListener() {
                        @Override
                        public void onTerminated(
                                org.loomsip.dialog.DialogHandle dialog,
                                DialogTerminationReason reason
                        ) {
                            terminated.complete(reason);
                        }

                        @Override
                        public void onFailure(org.loomsip.dialog.DialogHandle dialog, Throwable cause) {
                            failure.complete(cause);
                        }

                        @Override
                        public void onManagerFailure(Throwable cause) {
                            failure.complete(cause);
                        }
                    },
                    (transaction, response, context) -> {
                        if (response.statusCode() >= 200) {
                            success.complete(response);
                        }
                    },
                    (transaction, request, context) -> { },
                    nonInviteClient(failure, new CompletableFuture<>()),
                    (transaction, request, context) -> { },
                    null,
                    null
            );
            server = ScenarioEndpoint.create(
                    serverTransport,
                    new ConnectionAwareMessageSender(new TransportSelector(serverRegistry)),
                    clientEndpoint,
                    SipTimerConfig.DEFAULT,
                    lifecycle(failure),
                    (transaction, response, context) -> { },
                    (transaction, request, context) -> transaction.sendResponse(inviteExpirySuccess(request, serverEndpoint)),
                    nonInviteClient(failure, new CompletableFuture<>()),
                    (transaction, request, context) -> { },
                    null,
                    null
            );
            clientInbound.bind(client.dispatcher());
            serverInbound.bind(server.dispatcher());

            client.inviteTransactions().sendInvite(invite(clientEndpoint, TransportProtocol.UDP), serverEndpoint);
            assertEquals(200, success.get(5, TimeUnit.SECONDS).statusCode());
            DialogId id = new DialogId("6g-prack@example.com", "client-tag", "server-tag");
            assertTrue(client.dialogs().find(id).isPresent());
            client.scheduler().advanceBy(Duration.ofSeconds(120));
            assertEquals(DialogTerminationReason.SESSION_EXPIRED, terminated.get(5, TimeUnit.SECONDS));
            assertFalse(client.dialogs().find(id).isPresent());
            assertFalse(failure.isDone(), () -> "unexpected scenario failure: " + failure.join());
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

    private static void runScenario(
            TransportProtocol protocol,
            SslContext serverContext,
            SslContext clientContext
    ) throws Exception {
        ScenarioInboundHandler clientInbound = new ScenarioInboundHandler();
        ScenarioInboundHandler serverInbound = new ScenarioInboundHandler();
        SipTransport clientTransport = transport(protocol, clientInbound, serverContext, clientContext);
        SipTransport serverTransport = transport(protocol, serverInbound, serverContext, clientContext);
        TransportRegistry clientRegistry = registry(protocol, clientTransport);
        TransportRegistry serverRegistry = registry(protocol, serverTransport);
        VirtualSipScheduler clientReliableScheduler = new VirtualSipScheduler();
        VirtualSipScheduler serverReliableScheduler = new VirtualSipScheduler();
        ReliableProvisionalManager clientReliable = reliableManager(clientReliableScheduler);
        ReliableProvisionalManager serverReliable = reliableManager(serverReliableScheduler);
        ScenarioEndpoint client = null;
        ScenarioEndpoint server = null;
        try {
            clientRegistry.start();
            serverRegistry.start();
            TransportEndpoint clientEndpoint = clientTransport.localEndpoint();
            TransportEndpoint serverEndpoint = targetEndpoint(protocol, serverTransport.localEndpoint());
            TransportEndpoint serverRemote = targetEndpoint(protocol, clientEndpoint);
            CompletableFuture<Throwable> failure = new CompletableFuture<>();
            CompletableFuture<SipResponse> inviteSuccess = new CompletableFuture<>();
            CompletableFuture<SipRequest> prack = new CompletableFuture<>();
            BlockingQueue<SipRequest> updates = new LinkedBlockingQueue<>();
            CompletableFuture<SipResponse> updateSuccess = new CompletableFuture<>();
            AtomicInteger updateCount = new AtomicInteger();
            AtomicReference<InviteServerHandle> inviteHandle = new AtomicReference<>();
            AtomicReference<SipRequest> inviteRequest = new AtomicReference<>();

            client = ScenarioEndpoint.create(
                    clientTransport,
                    new ConnectionAwareMessageSender(new TransportSelector(clientRegistry)),
                    serverEndpoint,
                    SipTimerConfig.DEFAULT,
                    lifecycle(failure),
                    new org.loomsip.transaction.invite.InviteClientListener() {
                        @Override
                        public void onResponse(
                                org.loomsip.transaction.invite.InviteClientHandle transaction,
                                SipResponse response,
                                TransportContext context
                        ) {
                            if (response.statusCode() >= 200) {
                                inviteSuccess.complete(response);
                            }
                        }

                        @Override
                        public void onLayerError(Throwable cause) {
                            failure.complete(cause);
                        }
                    },
                    (transaction, request, context) -> { },
                    nonInviteClient(failure, updateSuccess),
                    (transaction, request, context) -> { },
                    clientReliable,
                    null
            );
            server = ScenarioEndpoint.create(
                    serverTransport,
                    new ConnectionAwareMessageSender(new TransportSelector(serverRegistry)),
                    serverRemote,
                    SipTimerConfig.DEFAULT,
                    lifecycle(failure),
                    (transaction, response, context) -> { },
                    new InviteServerListener() {
                        @Override
                        public void onInvite(InviteServerHandle transaction, SipRequest request, TransportContext context) {
                            inviteHandle.set(transaction);
                            inviteRequest.set(request);
                            transaction.sendResponse(provisional(request, serverEndpoint));
                        }

                        @Override
                        public void onLayerError(Throwable cause) {
                            failure.complete(cause);
                        }
                    },
                    nonInviteClient(failure, updateSuccess),
                    new NonInviteServerListener() {
                        @Override
                        public void onRequest(
                                ServerTransactionHandle transaction,
                                SipRequest request,
                                TransportContext context
                        ) {
                            if (request.method() == SipMethod.PRACK) {
                                prack.complete(request);
                                transaction.sendResponse(SipResponses.createResponse(request, 200, "OK", "server-tag"));
                                InviteServerHandle selected = inviteHandle.get();
                                if (selected == null) {
                                    failure.complete(new AssertionError("PRACK arrived before INVITE callback"));
                                    return;
                                }
                                selected.sendResponse(inviteSuccess(inviteRequest.get(), serverEndpoint));
                            } else if (request.method() == SipMethod.UPDATE) {
                                updates.add(request);
                                if (protocol == TransportProtocol.UDP && updateCount.incrementAndGet() == 1) {
                                    transaction.sendResponse(responseWithHeaders(
                                            SipResponses.createResponse(
                                                    request,
                                                    422,
                                                    "Session Interval Too Small",
                                                    "server-tag"
                                            ),
                                            SipHeaders.builder().add("Min-SE", "180").build()
                                    ));
                                } else {
                                    transaction.sendResponse(SipResponses.createResponse(request, 200, "OK", "server-tag"));
                                }
                            } else {
                                failure.complete(new AssertionError("unexpected Non-INVITE method: " + request.method()));
                            }
                        }

                        @Override
                        public void onLayerError(Throwable cause) {
                            failure.complete(cause);
                        }
                    },
                    serverReliable,
                    null
            );
            clientInbound.bind(client.dispatcher());
            serverInbound.bind(server.dispatcher());

            client.inviteTransactions().sendInvite(invite(clientEndpoint, protocol), serverEndpoint);
            SipRequest receivedPrack = await(prack, failure);
            assertEquals("1 1 INVITE", receivedPrack.headers().firstValue("RAck").orElseThrow());
            assertEquals(2, SipHeaderValues.cseq(receivedPrack.headers()).sequenceNumber());
            assertEquals(200, await(inviteSuccess, failure).statusCode());

            client.scheduler().advanceBy(Duration.ofSeconds(60));
            SipRequest refresh = Objects.requireNonNull(updates.poll(5, TimeUnit.SECONDS));
            assertEquals("120;refresher=uac", refresh.headers().firstValue("Session-Expires").orElseThrow());
            assertEquals(SipMethod.UPDATE, refresh.method());
            assertEquals(3, SipHeaderValues.cseq(refresh.headers()).sequenceNumber());
            if (protocol == TransportProtocol.UDP) {
                SipRequest retry = Objects.requireNonNull(updates.poll(5, TimeUnit.SECONDS));
                assertEquals("180;refresher=uac", retry.headers().firstValue("Session-Expires").orElseThrow());
                assertEquals("180", retry.headers().firstValue("Min-SE").orElseThrow());
                assertEquals(4, SipHeaderValues.cseq(retry.headers()).sequenceNumber());
            }
            assertEquals(200, await(updateSuccess, failure).statusCode());
            assertFalse(failure.isDone(), () -> "unexpected scenario failure: " + failure.join());
        } finally {
            if (client != null) {
                client.close();
            }
            if (server != null) {
                server.close();
            }
            clientReliable.close();
            serverReliable.close();
            clientReliableScheduler.close();
            serverReliableScheduler.close();
            clientRegistry.close();
            serverRegistry.close();
        }
    }

    private static NonInviteClientListener nonInviteClient(
            CompletableFuture<Throwable> failure,
            CompletableFuture<SipResponse> updateSuccess
    ) {
        return new NonInviteClientListener() {
            @Override
            public void onResponse(
                    org.loomsip.transaction.noninvite.ClientTransactionHandle transaction,
                    SipResponse response,
                    TransportContext context
            ) {
                if (transaction.originalRequest().method() == SipMethod.UPDATE
                        && response.statusCode() >= 200
                        && response.statusCode() < 300) {
                    updateSuccess.complete(response);
                }
            }

            @Override
            public void onLayerError(Throwable cause) {
                failure.complete(cause);
            }
        };
    }

    private static ReliableProvisionalManager reliableManager(VirtualSipScheduler scheduler) {
        return new ReliableProvisionalManager(
                new ReliableProvisionalConfig(8, 16, 4),
                scheduler,
                SipTimerConfig.DEFAULT,
                Runnable::run,
                cause -> {
                    throw new AssertionError(cause);
                }
        );
    }

    private static org.loomsip.dialog.DialogLifecycleListener lifecycle(CompletableFuture<Throwable> failure) {
        return new org.loomsip.dialog.DialogLifecycleListener() {
            @Override
            public void onFailure(org.loomsip.dialog.DialogHandle dialog, Throwable cause) {
                failure.complete(cause);
            }

            @Override
            public void onManagerFailure(Throwable cause) {
                failure.complete(cause);
            }
        };
    }

    private static SipRequest invite(TransportEndpoint local, TransportProtocol protocol) {
        return new SipRequest(
                SipMethod.INVITE,
                SipUri.parse(protocol == TransportProtocol.TLS ? "sips:bob@example.com" : "sip:bob@example.com"),
                SipVersion.SIP_2_0,
                SipHeaders.builder()
                        .add("Via", "SIP/2.0/" + protocol + " " + sentBy(local)
                                + ";branch=z9hG4bK-6g-prack-invite")
                        .add("Max-Forwards", "70")
                        .add("From", "<sip:alice@example.com>;tag=client-tag")
                        .add("To", "<sip:bob@example.com>")
                        .add("Call-ID", "6g-prack@example.com")
                        .add("CSeq", "1 INVITE")
                        .add("Contact", contact("alice", local))
                        .add("Supported", "100rel, timer")
                        .build(),
                SipBody.empty()
        );
    }

    private static SipResponse provisional(SipRequest request, TransportEndpoint server) {
        return responseWithHeaders(
                SipResponses.createResponse(request, 183, "Session Progress", "server-tag"),
                SipHeaders.builder()
                        .add("Require", "100rel")
                        .add("Contact", contact("bob", server))
                        .build()
        );
    }

    private static SipResponse inviteSuccess(SipRequest invite, TransportEndpoint server) {
        return responseWithHeaders(
                SipResponses.createResponse(invite, 200, "OK", "server-tag"),
                SipHeaders.builder()
                        .add("Contact", contact("bob", server))
                        .add("Session-Expires", "120;refresher=uac")
                        .build()
        );
    }

    private static SipResponse inviteExpirySuccess(SipRequest invite, TransportEndpoint server) {
        return responseWithHeaders(
                SipResponses.createResponse(invite, 200, "OK", "server-tag"),
                SipHeaders.builder()
                        .add("Contact", contact("bob", server))
                        .add("Session-Expires", "120;refresher=uas")
                        .build()
        );
    }

    private static SipResponse responseWithHeaders(SipResponse response, SipHeaders additional) {
        SipHeaders.Builder headers = response.headers().toBuilder();
        additional.entries().forEach(header -> headers.add(header.name(), header.value()));
        return new SipResponse(response.version(), response.statusCode(), response.reasonPhrase(), headers.build(), response.body());
    }

    private static TransportRegistry registry(TransportProtocol protocol, SipTransport transport) {
        TransportRegistry registry = new TransportRegistry();
        registry.register(protocol, transport);
        return registry;
    }

    private static SipTransport transport(
            TransportProtocol protocol,
            ScenarioInboundHandler inbound,
            SslContext serverContext,
            SslContext clientContext
    ) {
        return switch (protocol) {
            case UDP -> new NettyUdpTransport(
                    new UdpTransportConfig(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0)), inbound
            );
            case TCP -> new NettyTcpTransport(
                    new TcpTransportConfig(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0)), inbound
            );
            case TLS -> new NettyTlsTransport(
                    new TlsTransportConfig(
                            new InetSocketAddress(InetAddress.getLoopbackAddress(), 0),
                            org.loomsip.codec.StreamBufferLimits.DEFAULT,
                            org.loomsip.transport.ConnectionLimits.DEFAULT,
                            org.loomsip.transport.WriteQueueLimits.DEFAULT,
                            Objects.requireNonNull(serverContext, "serverContext"),
                            Objects.requireNonNull(clientContext, "clientContext"),
                            Duration.ofSeconds(5),
                            true,
                            "6g-prack-profile",
                            List.of(),
                            List.of()
                    ),
                    inbound
            );
        };
    }

    private static TransportEndpoint targetEndpoint(TransportProtocol protocol, TransportEndpoint endpoint) {
        if (protocol != TransportProtocol.TLS) {
            return endpoint;
        }
        return TransportEndpoint.tls(new InetSocketAddress("localhost", endpoint.address().getPort()));
    }

    private static String contact(String user, TransportEndpoint endpoint) {
        return "<sip:" + user + "@" + sentBy(endpoint) + ">";
    }

    private static String sentBy(TransportEndpoint endpoint) {
        String host = endpoint.address().getAddress() == null
                ? endpoint.address().getHostString()
                : endpoint.address().getAddress().getHostAddress();
        return host + ':' + endpoint.address().getPort();
    }

    private static <T> T await(CompletableFuture<T> result, CompletableFuture<Throwable> failure) throws Exception {
        try {
            return result.get(5, TimeUnit.SECONDS);
        } catch (java.util.concurrent.TimeoutException timeout) {
            if (failure.isDone()) {
                throw new AssertionError("scenario failed before expected message", failure.join());
            }
            throw timeout;
        }
    }
}
