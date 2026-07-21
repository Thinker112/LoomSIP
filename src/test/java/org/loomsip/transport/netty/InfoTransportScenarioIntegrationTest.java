package org.loomsip.transport.netty;

import io.netty.handler.ssl.SslContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.loomsip.info.InfoDispatcher;
import org.loomsip.info.InfoRequest;
import org.loomsip.info.InfoResponse;
import org.loomsip.message.SipBody;
import org.loomsip.message.SipHeaders;
import org.loomsip.message.SipMethod;
import org.loomsip.message.SipRequest;
import org.loomsip.message.SipResponse;
import org.loomsip.message.SipResponses;
import org.loomsip.message.SipUri;
import org.loomsip.message.SipVersion;
import org.loomsip.message.header.InfoPackageHeaderValue;
import org.loomsip.message.header.SipHeaderValues;
import org.loomsip.testkit.ScenarioEndpoint;
import org.loomsip.testkit.ScenarioInboundHandler;
import org.loomsip.transaction.ConnectionAwareMessageSender;
import org.loomsip.transaction.invite.InviteClientListener;
import org.loomsip.transaction.invite.InviteServerListener;
import org.loomsip.transaction.noninvite.NonInviteClientListener;
import org.loomsip.transaction.noninvite.NonInviteServerListener;
import org.loomsip.transaction.timer.SipTimerConfig;
import org.loomsip.transport.SendResult;
import org.loomsip.transport.SipTransport;
import org.loomsip.transport.TransportContext;
import org.loomsip.transport.TransportEndpoint;
import org.loomsip.transport.TransportProtocol;
import org.loomsip.transport.TransportRegistry;
import org.loomsip.transport.TransportSelector;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 6G-B real-transport acceptance for Dialog creation and packaged INFO.
 *
 * <pre>{@code
 * INVITE / 200 creates Dialog
 *             |
 *             v
 * DialogHandle.sendInfo
 *      |                 |
 *      v                 v
 * registered package   unknown package
 *      |                 |
 *      v                 v
 * 200 + body       469 + Recv-Info
 * }</pre>
 */
@Timeout(30)
class InfoTransportScenarioIntegrationTest {

    @Test
    void completesUdpDialogAndPackagedInfoScenarios() throws Exception {
        runScenario(TransportProtocol.UDP, null, null);
    }

    @Test
    void completesTcpDialogAndPackagedInfoScenarios() throws Exception {
        runScenario(TransportProtocol.TCP, null, null);
    }

    @Test
    void completesTlsDialogAndPackagedInfoScenarios() throws Exception {
        try (TestTlsMaterial material = TestTlsMaterial.create("localhost")) {
            runScenario(TransportProtocol.TLS, material.serverContext(), material.trustedClientContext());
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
        CompletableFuture<Throwable> failure = new CompletableFuture<>();
        CompletableFuture<SipResponse> inviteSuccess = new CompletableFuture<>();
        BlockingQueue<SipResponse> infoResponses = new LinkedBlockingQueue<>();
        AtomicInteger fallbackInfoCallbacks = new AtomicInteger();
        AtomicReference<ScenarioEndpoint> clientReference = new AtomicReference<>();

        clientRegistry.start();
        serverRegistry.start();
        ScenarioEndpoint client = null;
        ScenarioEndpoint server = null;
        try {
            TransportEndpoint clientEndpoint = clientTransport.localEndpoint();
            TransportEndpoint serverEndpoint = targetEndpoint(protocol, serverTransport.localEndpoint());
            TransportEndpoint serverRemote = targetEndpoint(protocol, clientEndpoint);
            InfoDispatcher serverInfo = new InfoDispatcher();
            serverInfo.register(new InfoPackageHeaderValue("conference"), request ->
                    CompletableFuture.completedFuture(new InfoResponse(
                            200,
                            "OK",
                            SipHeaders.builder().add("X-Info-Handled", "conference").build(),
                            SipBody.of("accepted".getBytes(StandardCharsets.US_ASCII))
                    ))
            );
            client = ScenarioEndpoint.create(
                    clientTransport,
                    new ConnectionAwareMessageSender(new TransportSelector(clientRegistry)),
                    serverEndpoint,
                    SipTimerConfig.DEFAULT,
                    new org.loomsip.dialog.DialogLifecycleListener() {
                        @Override
                        public void onFailure(org.loomsip.dialog.DialogHandle dialog, Throwable cause) {
                            failure.complete(cause);
                        }

                        @Override
                        public void onManagerFailure(Throwable cause) {
                            failure.complete(cause);
                        }
                    },
                    new InviteClientListener() {
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
                    (transaction, request, context) -> {
                    },
                    new NonInviteClientListener() {
                        @Override
                        public void onResponse(
                                org.loomsip.transaction.noninvite.ClientTransactionHandle transaction,
                                SipResponse response,
                                TransportContext context
                        ) {
                            infoResponses.add(response);
                        }

                        @Override
                        public void onLayerError(Throwable cause) {
                            failure.complete(cause);
                        }
                    },
                    (transaction, request, context) -> {
                    },
                    null
            );
            server = ScenarioEndpoint.create(
                    serverTransport,
                    new ConnectionAwareMessageSender(new TransportSelector(serverRegistry)),
                    serverRemote,
                    SipTimerConfig.DEFAULT,
                    new org.loomsip.dialog.DialogLifecycleListener() {
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
                    },
                    new InviteServerListener() {
                        @Override
                        public void onInvite(
                                org.loomsip.transaction.invite.InviteServerHandle transaction,
                                SipRequest request,
                                TransportContext context
                        ) {
                            transaction.sendResponse(success(request, "server-tag", contact("bob", serverEndpoint)));
                        }

                        @Override
                        public void onLayerError(Throwable cause) {
                            failure.complete(cause);
                        }
                    },
                    (transaction, response, context) -> {
                    },
                    new NonInviteServerListener() {
                        @Override
                        public void onRequest(
                                org.loomsip.transaction.noninvite.ServerTransactionHandle transaction,
                                SipRequest request,
                                TransportContext context
                        ) {
                            fallbackInfoCallbacks.incrementAndGet();
                            transaction.sendResponse(SipResponses.createResponse(request, 200, "OK", "server-tag"));
                        }
                    },
                    serverInfo
            );
            clientReference.set(client);
            clientInbound.bind(client.dispatcher());
            serverInbound.bind(server.dispatcher());

            client.inviteTransactions().sendInvite(initialInvite(clientEndpoint, protocol), serverEndpoint);
            assertEquals(200, inviteSuccess.get(5, TimeUnit.SECONDS).statusCode());

            org.loomsip.dialog.DialogHandle dialog = client.dialogs().find(new org.loomsip.dialog.DialogId(
                    "6g-info@example.com", "client-tag", "server-tag"
            )).orElseThrow();
            SipBody body = SipBody.of("conference-state".getBytes(StandardCharsets.US_ASCII));
            dialog.sendInfo(new InfoRequest(
                    new InfoPackageHeaderValue("conference"),
                    SipHeaders.builder().add("Content-Type", "application/conference-info+xml").build(),
                    body
            )).toCompletableFuture().get(5, TimeUnit.SECONDS);

            SipResponse success = Objects.requireNonNull(infoResponses.poll(5, TimeUnit.SECONDS));
            assertEquals(200, success.statusCode());
            assertEquals("conference", success.headers().firstValue("X-Info-Handled").orElseThrow());
            assertEquals("accepted", new String(success.body().bytes(), StandardCharsets.US_ASCII));

            dialog.sendInfo(new InfoRequest(
                    new InfoPackageHeaderValue("x-vendor"),
                    SipHeaders.empty(),
                    SipBody.empty()
            )).toCompletableFuture().get(5, TimeUnit.SECONDS);

            SipResponse unsupported = Objects.requireNonNull(infoResponses.poll(5, TimeUnit.SECONDS));
            assertEquals(469, unsupported.statusCode());
            assertEquals("conference", unsupported.headers().firstValue("Recv-Info").orElseThrow());
            assertEquals(0, fallbackInfoCallbacks.get());
            assertEquals(3, SipHeaderValues.cseq(unsupported.headers()).sequenceNumber());
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
                    new UdpTransportConfig(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0)),
                    inbound
            );
            case TCP -> new NettyTcpTransport(
                    new TcpTransportConfig(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0)),
                    inbound
            );
            case TLS -> new NettyTlsTransport(
                    new TlsTransportConfig(
                            new InetSocketAddress(InetAddress.getLoopbackAddress(), 0),
                            org.loomsip.codec.StreamBufferLimits.DEFAULT,
                            org.loomsip.transport.ConnectionLimits.DEFAULT,
                            org.loomsip.transport.WriteQueueLimits.DEFAULT,
                            Objects.requireNonNull(serverContext, "serverContext"),
                            Objects.requireNonNull(clientContext, "clientContext"),
                            java.time.Duration.ofSeconds(5),
                            true,
                            "6g-info-profile",
                            java.util.List.of(),
                            java.util.List.of()
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

    private static SipRequest initialInvite(TransportEndpoint local, TransportProtocol protocol) {
        return new SipRequest(
                SipMethod.INVITE,
                SipUri.parse(protocol == TransportProtocol.TLS ? "sips:bob@example.com" : "sip:bob@example.com"),
                SipVersion.SIP_2_0,
                SipHeaders.builder()
                        .add("Via", "SIP/2.0/" + protocol + " " + sentBy(local)
                                + ";branch=z9hG4bK-6g-info-invite")
                        .add("Max-Forwards", "70")
                        .add("From", "<sip:alice@example.com>;tag=client-tag")
                        .add("To", "<sip:bob@example.com>")
                        .add("Call-ID", "6g-info@example.com")
                        .add("CSeq", "1 INVITE")
                        .add("Contact", contact("alice", local))
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

    private static String contact(String user, TransportEndpoint endpoint) {
        return "<sip:" + user + "@" + sentBy(endpoint) + ">";
    }

    private static String sentBy(TransportEndpoint endpoint) {
        String host = endpoint.address().getAddress() == null
                ? endpoint.address().getHostString()
                : endpoint.address().getAddress().getHostAddress();
        return host + ':' + endpoint.address().getPort();
    }
}
