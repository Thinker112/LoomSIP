package org.loomsip.transport.netty;

import io.netty.handler.ssl.SslContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.loomsip.auth.ClientAuthenticationCoordinator;
import org.loomsip.auth.ClientDigestCredential;
import org.loomsip.auth.DigestAlgorithm;
import org.loomsip.auth.DigestCharset;
import org.loomsip.auth.DigestCredentialRecord;
import org.loomsip.auth.DigestNonceManager;
import org.loomsip.auth.ServerAuthenticationGate;
import org.loomsip.auth.ServerAuthenticationPolicy;
import org.loomsip.exchange.ClientRequestExchange;
import org.loomsip.exchange.RequestRetryPolicy;
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
import org.loomsip.transaction.noninvite.ClientTransactionHandle;
import org.loomsip.transaction.noninvite.NonInviteClientListener;
import org.loomsip.transaction.noninvite.NonInviteServerListener;
import org.loomsip.transaction.noninvite.ServerTransactionHandle;
import org.loomsip.transaction.timer.SipTimerConfig;
import org.loomsip.transport.SipTransport;
import org.loomsip.transport.TransportContext;
import org.loomsip.transport.TransportEndpoint;
import org.loomsip.transport.TransportProtocol;
import org.loomsip.transport.TransportRegistry;
import org.loomsip.transport.TransportSelector;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 6G-C real-transport acceptance for an origin Digest challenge and retry.
 *
 * <pre>{@code
 * OPTIONS (initial Via/CSeq)
 *            |
 *            v
 * UAS Digest Gate --- 401 WWW-Authenticate ---> UAC Coordinator
 *                                                 |
 *                                                 v
 *                              rebuilt OPTIONS (fresh Via/CSeq + Authorization)
 *                                                 |
 *                                                 v
 *                                      UAS application ---> 200 OK
 * }</pre>
 *
 * <p>The scenario deliberately uses OPTIONS so it verifies the authentication
 * exchange above a Non-INVITE transaction without depending on Dialog CSeq
 * ownership. It confirms that the retry retains logical request identity,
 * route, and payload while creating a new transaction identity.</p>
 */
@Timeout(30)
class DigestTransportScenarioIntegrationTest {

    private static final String REALM = "loomsip-test";
    private static final String USERNAME = "alice";
    private static final String PASSWORD = "secret";
    private static final String CALL_ID = "6g-digest@example.com";
    private static final String FROM = "<sip:alice@example.com>;tag=client-tag";
    private static final String TO = "<sip:bob@example.com>";
    private static final String ROUTE = "<sip:edge.example.com;lr>";
    private static final SipBody BODY = SipBody.of("retained-body".getBytes(StandardCharsets.US_ASCII));

    @Test
    void retriesDigestChallengeOverUdp() throws Exception {
        runScenario(TransportProtocol.UDP, null, null);
    }

    @Test
    void retriesDigestChallengeOverTcp() throws Exception {
        runScenario(TransportProtocol.TCP, null, null);
    }

    @Test
    void retriesDigestChallengeOverTls() throws Exception {
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
        ExecutorService authenticationExecutor = Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name("loomsip-6g-digest-", 0).factory()
        );
        ScenarioEndpoint client = null;
        ScenarioEndpoint server = null;
        try {
            clientRegistry.start();
            serverRegistry.start();
            TransportEndpoint clientEndpoint = clientTransport.localEndpoint();
            TransportEndpoint serverEndpoint = targetEndpoint(protocol, serverTransport.localEndpoint());
            TransportEndpoint serverRemote = targetEndpoint(protocol, clientEndpoint);
            CompletableFuture<Throwable> failure = new CompletableFuture<>();
            List<SipRequest> received = new ArrayList<>();
            AtomicInteger applicationCalls = new AtomicInteger();
            AtomicReference<ClientAuthenticationCoordinator<ClientTransactionHandle>> coordinator = new AtomicReference<>();
            AtomicReference<ScenarioEndpoint> clientReference = new AtomicReference<>();

            client = ScenarioEndpoint.create(
                    clientTransport,
                    new ConnectionAwareMessageSender(new TransportSelector(clientRegistry)),
                    serverEndpoint,
                    SipTimerConfig.DEFAULT,
                    lifecycle(failure),
                    (transaction, response, context) -> { },
                    (transaction, request, context) -> { },
                    new NonInviteClientListener() {
                        @Override
                        public void onResponse(
                                ClientTransactionHandle transaction,
                                SipResponse response,
                                TransportContext context
                        ) {
                            ClientAuthenticationCoordinator<ClientTransactionHandle> selected = coordinator.get();
                            if (selected == null) {
                                failure.complete(new AssertionError("response arrived before coordinator startup"));
                                return;
                            }
                            selected.onResponse(response).whenComplete((ignored, cause) -> {
                                if (cause != null) {
                                    failure.complete(cause);
                                }
                            });
                        }

                        @Override
                        public void onLayerError(Throwable cause) {
                            failure.complete(cause);
                        }
                    },
                    (transaction, request, context) -> { },
                    null
            );
            clientReference.set(client);

            ServerAuthenticationGate gate = gate();
            NonInviteServerListener application = new NonInviteServerListener() {
                @Override
                public void onRequest(ServerTransactionHandle transaction, SipRequest request, TransportContext context) {
                    applicationCalls.incrementAndGet();
                    synchronized (received) {
                        received.add(request);
                    }
                    transaction.sendResponse(SipResponses.createResponse(request, 200, "OK", "server-tag"));
                }

                @Override
                public void onLayerError(Throwable cause) {
                    failure.complete(cause);
                }
            };
            server = ScenarioEndpoint.create(
                    serverTransport,
                    new ConnectionAwareMessageSender(new TransportSelector(serverRegistry)),
                    serverRemote,
                    SipTimerConfig.DEFAULT,
                    lifecycle(failure),
                    (transaction, response, context) -> { },
                    (transaction, request, context) -> { },
                    (transaction, response, context) -> { },
                    gate.nonInviteListener(application, authenticationExecutor),
                    null
            );
            clientInbound.bind(client.dispatcher());
            serverInbound.bind(server.dispatcher());

            SipRequest initial = request(clientEndpoint, protocol, 1, "initial");
            ClientRequestExchange<ClientTransactionHandle> exchange = new ClientRequestExchange<>(
                    initial,
                    context -> clientReference.get().nonInviteTransactions().sendRequest(context.request(), serverEndpoint),
                    new RequestRetryPolicy(2),
                    authenticationExecutor,
                    failure::complete,
                    16
            );
            ClientAuthenticationCoordinator<ClientTransactionHandle> created = new ClientAuthenticationCoordinator<>(
                    exchange,
                    ignored -> CompletableFuture.completedFuture(Optional.of(
                            new ClientDigestCredential(USERNAME, PASSWORD.toCharArray())
                    )),
                    (previous, scope, challenge, authorization) -> CompletableFuture.completedFuture(
                            previous.toBuilder()
                                    .replaceHeader("Via", "SIP/2.0/" + protocol + " " + sentBy(clientEndpoint)
                                            + ";branch=z9hG4bK-6g-digest-retry")
                                    .replaceHeader("CSeq", "2 OPTIONS")
                                    .build()
                    ),
                    authenticationExecutor
            );
            coordinator.set(created);
            created.start().toCompletableFuture().get(5, TimeUnit.SECONDS);

            SipResponse finalResponse = created.completion().toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertEquals(200, finalResponse.statusCode());
            assertEquals(1, applicationCalls.get());
            assertFalse(failure.isDone(), () -> "unexpected scenario failure: " + failure.join());
            synchronized (received) {
                assertEquals(1, received.size());
                SipRequest retry = received.getFirst();
                assertTrue(retry.headers().firstValue("Authorization").orElseThrow().startsWith("Digest "));
                assertNotEquals(initial.headers().firstValue("Via").orElseThrow(), retry.headers().firstValue("Via").orElseThrow());
                assertEquals(2, SipHeaderValues.cseq(retry.headers()).sequenceNumber());
                assertEquals(CALL_ID, retry.headers().firstValue("Call-ID").orElseThrow());
                assertEquals(FROM, retry.headers().firstValue("From").orElseThrow());
                assertEquals(TO, retry.headers().firstValue("To").orElseThrow());
                assertEquals(ROUTE, retry.headers().firstValue("Route").orElseThrow());
                assertArrayEquals(BODY.bytes(), retry.body().bytes());
            }
            created.close();
        } finally {
            if (client != null) {
                client.close();
            }
            if (server != null) {
                server.close();
            }
            clientRegistry.close();
            serverRegistry.close();
            authenticationExecutor.shutdownNow();
        }
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

    private static ServerAuthenticationGate gate() {
        ServerAuthenticationPolicy policy = new ServerAuthenticationPolicy(
                REALM,
                java.util.Set.of(DigestAlgorithm.MD5),
                DigestCharset.ISO_8859_1,
                Duration.ofMinutes(1),
                16,
                8,
                Optional.empty()
        );
        String ha1 = hash(USERNAME + ':' + REALM + ':' + PASSWORD);
        return new ServerAuthenticationGate(
                policy,
                (realm, username, algorithm) -> CompletableFuture.completedFuture(
                        realm.equals(REALM) && username.equals(USERNAME) && algorithm == DigestAlgorithm.MD5
                                ? Optional.of(new DigestCredentialRecord(USERNAME, REALM, algorithm, ha1))
                                : Optional.empty()
                ),
                new DigestNonceManager(policy)
        );
    }

    private static SipRequest request(
            TransportEndpoint local,
            TransportProtocol protocol,
            long cseq,
            String branch
    ) {
        return new SipRequest(
                SipMethod.OPTIONS,
                SipUri.parse(protocol == TransportProtocol.TLS ? "sips:bob@example.com" : "sip:bob@example.com"),
                SipVersion.SIP_2_0,
                SipHeaders.builder()
                        .add("Via", "SIP/2.0/" + protocol + " " + sentBy(local) + ";branch=z9hG4bK-6g-digest-" + branch)
                        .add("Max-Forwards", "70")
                        .add("From", FROM)
                        .add("To", TO)
                        .add("Call-ID", CALL_ID)
                        .add("CSeq", cseq + " OPTIONS")
                        .add("Route", ROUTE)
                        .add("Content-Type", "application/example")
                        .build(),
                BODY
        );
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
                            "6g-digest-profile",
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

    private static String sentBy(TransportEndpoint endpoint) {
        String host = endpoint.address().getAddress() == null
                ? endpoint.address().getHostString()
                : endpoint.address().getAddress().getHostAddress();
        return host + ':' + endpoint.address().getPort();
    }

    private static String hash(String text) {
        try {
            return java.util.HexFormat.of().formatHex(
                    MessageDigest.getInstance("MD5").digest(text.getBytes(StandardCharsets.ISO_8859_1))
            );
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new AssertionError(exception);
        }
    }
}
