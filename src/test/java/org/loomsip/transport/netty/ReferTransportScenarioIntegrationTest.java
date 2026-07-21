package org.loomsip.transport.netty;

import io.netty.handler.ssl.SslContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.loomsip.dialog.DialogLifecycleListener;
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
import org.loomsip.refer.ReferAcceptance;
import org.loomsip.refer.ReferNotifier;
import org.loomsip.refer.ReferServerListener;
import org.loomsip.refer.ReferSubscriptionProfile;
import org.loomsip.refer.ReferSubscriptionPublisher;
import org.loomsip.refer.SipfragStatus;
import org.loomsip.subscription.SubscriptionConfig;
import org.loomsip.subscription.SubscriptionManager;
import org.loomsip.subscription.SubscriptionPublisher;
import org.loomsip.subscription.SubscriptionRequestProfile;
import org.loomsip.testkit.ScenarioEndpoint;
import org.loomsip.testkit.ScenarioInboundHandler;
import org.loomsip.transaction.ConnectionAwareMessageSender;
import org.loomsip.transaction.noninvite.NonInviteClientListener;
import org.loomsip.transaction.noninvite.NonInviteServerListener;
import org.loomsip.transaction.timer.SipTimerConfig;
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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 7G real-transport acceptance for an out-of-dialog RFC 3515 REFER flow.
 *
 * <pre>{@code
 * REFER --> 202 Accepted --> 100 Trying NOTIFY --> 200 OK sipfrag NOTIFY
 * }</pre>
 */
@Timeout(30)
class ReferTransportScenarioIntegrationTest {

    @Test
    void completesReferSubscriptionOverUdp() throws Exception {
        runScenario(TransportProtocol.UDP, null, null, true);
    }

    @Test
    void completesReferSubscriptionOverTcp() throws Exception {
        runScenario(TransportProtocol.TCP, null, null, true);
    }

    @Test
    void completesReferSubscriptionOverTls() throws Exception {
        try (TestTlsMaterial material = TestTlsMaterial.create("localhost")) {
            runScenario(TransportProtocol.TLS, material.serverContext(), material.trustedClientContext());
        }
    }

    @Test
    void honorsReferSubFalseOverUdp() throws Exception {
        runScenario(TransportProtocol.UDP, null, null, false);
    }

    @Test
    void honorsReferSubFalseOverTcp() throws Exception {
        runScenario(TransportProtocol.TCP, null, null, false);
    }

    @Test
    void honorsReferSubFalseOverTls() throws Exception {
        try (TestTlsMaterial material = TestTlsMaterial.create("localhost")) {
            runScenario(TransportProtocol.TLS, material.serverContext(), material.trustedClientContext(), false);
        }
    }

    @Test
    void closingSubscriptionAfterInitialNotifyRejectsLateFinalProgressOverUdp() throws Exception {
        runScenario(TransportProtocol.UDP, null, null, true, false, true);
    }

    private static void runScenario(TransportProtocol protocol, SslContext serverContext, SslContext clientContext)
            throws Exception {
        runScenario(protocol, serverContext, clientContext, true);
    }

    private static void runScenario(
            TransportProtocol protocol,
            SslContext serverContext,
            SslContext clientContext,
            boolean referSub
    )
            throws Exception {
        runScenario(protocol, serverContext, clientContext, referSub, true, false);
    }

    private static void runScenario(
            TransportProtocol protocol,
            SslContext serverContext,
            SslContext clientContext,
            boolean referSub,
            boolean sendFinalAfterInitial,
            boolean closeAfterInitial
    )
            throws Exception {
        ScenarioInboundHandler clientInbound = new ScenarioInboundHandler();
        ScenarioInboundHandler serverInbound = new ScenarioInboundHandler();
        SipTransport clientTransport = transport(protocol, clientInbound, serverContext, clientContext);
        SipTransport serverTransport = transport(protocol, serverInbound, serverContext, clientContext);
        TransportRegistry clientRegistry = registry(protocol, clientTransport);
        TransportRegistry serverRegistry = registry(protocol, serverTransport);
        SubscriptionManager serverSubscriptions = new SubscriptionManager(
                SubscriptionConfig.DEFAULT, Runnable::run, failure -> { throw new AssertionError(failure); }
        );
        List<ReferSubscriptionPublisher> publishers = new CopyOnWriteArrayList<>();
        CompletableFuture<Throwable> failure = new CompletableFuture<>();
        CompletableFuture<SipResponse> accepted = new CompletableFuture<>();
        CompletableFuture<Void> initialDelivered = new CompletableFuture<>();
        BlockingQueue<SipfragStatus> notifications = new LinkedBlockingQueue<>();
        AtomicReference<ScenarioEndpoint> serverReference = new AtomicReference<>();
        AtomicInteger branches = new AtomicInteger();
        ScenarioEndpoint client = null;
        ScenarioEndpoint server = null;
        try {
            clientRegistry.start();
            serverRegistry.start();
            TransportEndpoint clientTarget = targetEndpoint(protocol, clientTransport.localEndpoint());
            TransportEndpoint serverTarget = targetEndpoint(protocol, serverTransport.localEndpoint());

            NonInviteServerListener clientServer = new NonInviteServerListener() {
                @Override
                public void onRequest(
                        org.loomsip.transaction.noninvite.ServerTransactionHandle transaction,
                        SipRequest request,
                        TransportContext context
                ) {
                    try {
                        if (!SipMethod.NOTIFY.equals(request.method())) {
                            transaction.sendResponse(SipResponses.createResponse(request, 405, "Method Not Allowed"));
                            return;
                        }
                        assertEquals("refer", request.headers().firstValue("Event").orElseThrow());
                        assertEquals("message/sipfrag", request.headers().firstValue("Content-Type").orElseThrow());
                        SipfragStatus status = SipfragStatus.parse(request.body());
                        notifications.add(status);
                        if (status.statusCode() == 100) {
                            initialDelivered.complete(null);
                        }
                        transaction.sendResponse(SipResponses.createResponse(request, 200, "OK"));
                    } catch (Throwable cause) {
                        failure.complete(cause);
                    }
                }

                @Override public void onLayerError(Throwable cause) { failure.complete(cause); }
            };
            client = ScenarioEndpoint.create(
                    clientTransport,
                    new ConnectionAwareMessageSender(new TransportSelector(clientRegistry)),
                    serverTarget,
                    SipTimerConfig.DEFAULT,
                    lifecycle(failure),
                    (transaction, response, context) -> { },
                    (transaction, request, context) -> { },
                    referClient(accepted, failure),
                    clientServer,
                    null
            );

            ReferServerListener referServer = new ReferServerListener(
                    request -> CompletableFuture.completedFuture(new ReferAcceptance(202, "Accepted")),
                    serverSubscriptions,
                    (request, subscription, context) -> {
                        try {
                            ScenarioEndpoint endpoint = Objects.requireNonNull(serverReference.get(), "server endpoint");
                            ReferSubscriptionPublisher publisher = new ReferSubscriptionPublisher(
                                    serverSubscriptions,
                                    subscription,
                                    new ReferNotifier(new SubscriptionPublisher(endpoint.nonInviteTransactions(),
                                            requestProfile(serverTransport.localEndpoint()),
                                            () -> "z9hG4bK-7g-refer-" + branches.incrementAndGet())),
                                    new ReferSubscriptionProfile(
                                            uri(protocol, "alice"), uri(protocol, "bob"), uri(protocol, "alice"), 1,
                                            SipHeaders.empty(), clientTarget
                                    ),
                                    Runnable::run,
                                    failure::complete,
                                    8
                            );
                            publishers.add(publisher);
                            publisher.publish(new SipfragStatus(100, "Trying"));
                            if (sendFinalAfterInitial) {
                                initialDelivered.whenComplete((unused, deliveryFailure) -> {
                                    if (deliveryFailure != null) {
                                        failure.complete(deliveryFailure);
                                        return;
                                    }
                                    publisher.publish(new SipfragStatus(200, "OK"));
                                });
                            }
                        } catch (Throwable cause) {
                            failure.complete(cause);
                        }
                    },
                    (transaction, request, context) -> transaction.sendResponse(SipResponses.createResponse(request, 405, "Method Not Allowed")),
                    Runnable::run,
                    failure::complete,
                    () -> "server-tag"
            );
            server = ScenarioEndpoint.create(
                    serverTransport,
                    new ConnectionAwareMessageSender(new TransportSelector(serverRegistry)),
                    clientTarget,
                    SipTimerConfig.DEFAULT,
                    lifecycle(failure),
                    (transaction, response, context) -> { },
                    (transaction, request, context) -> { },
                    new NonInviteClientListener() {
                        @Override public void onResponse(
                                org.loomsip.transaction.noninvite.ClientTransactionHandle transaction,
                                SipResponse response,
                                TransportContext context
                        ) { }
                        @Override public void onLayerError(Throwable cause) { failure.complete(cause); }
                    },
                    referServer,
                    null
            );
            serverReference.set(server);
            clientInbound.bind(client.dispatcher());
            serverInbound.bind(server.dispatcher());

            client.nonInviteTransactions().sendRequest(refer(clientTransport.localEndpoint(), protocol, referSub), serverTarget);

            assertEquals(202, accepted.get(5, TimeUnit.SECONDS).statusCode());
            if (referSub) {
                assertEquals(new SipfragStatus(100, "Trying"), notifications.poll(5, TimeUnit.SECONDS));
                if (sendFinalAfterInitial) {
                    assertEquals(new SipfragStatus(200, "OK"), notifications.poll(5, TimeUnit.SECONDS));
                } else if (closeAfterInitial) {
                    serverSubscriptions.close();
                    assertThrows(Exception.class, () -> publishers.getFirst().publish(new SipfragStatus(200, "OK"))
                            .toCompletableFuture().join());
                    assertNull(notifications.poll(300, TimeUnit.MILLISECONDS));
                }
            } else {
                assertNull(notifications.poll(300, TimeUnit.MILLISECONDS));
            }
            assertEquals(0, serverSubscriptions.size());
            assertFalse(failure.isDone(), () -> "unexpected scenario failure: " + failure.join());
        } finally {
            publishers.forEach(ReferSubscriptionPublisher::close);
            serverSubscriptions.close();
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

    private static NonInviteClientListener referClient(
            CompletableFuture<SipResponse> accepted,
            CompletableFuture<Throwable> failure
    ) {
        return new NonInviteClientListener() {
            @Override
            public void onResponse(
                    org.loomsip.transaction.noninvite.ClientTransactionHandle transaction,
                    SipResponse response,
                    TransportContext context
            ) {
                try {
                    if (SipMethod.REFER.equals(SipHeaderValues.cseq(response.headers()).method())) {
                        accepted.complete(response);
                    }
                } catch (Throwable cause) {
                    failure.complete(cause);
                }
            }

            @Override public void onLayerError(Throwable cause) { failure.complete(cause); }
            @Override public void onTransportFailure(org.loomsip.transaction.noninvite.ClientTransactionHandle transaction, Throwable cause) { failure.complete(cause); }
            @Override public void onTimeout(org.loomsip.transaction.noninvite.ClientTransactionHandle transaction, org.loomsip.transaction.timer.SipTimer timer) { failure.complete(new IllegalStateException("REFER timed out")); }
        };
    }

    private static SipRequest refer(TransportEndpoint local, TransportProtocol protocol, boolean referSub) {
        SipHeaders.Builder headers = SipHeaders.builder()
                .add("Via", "SIP/2.0/" + protocol + " " + sentBy(local) + ";branch=z9hG4bK-7g-refer-client")
                .add("Max-Forwards", "70")
                .add("From", "<" + uri(protocol, "alice") + ">;tag=client-tag")
                .add("To", "<" + uri(protocol, "bob") + ">")
                .add("Call-ID", "7g-refer@example.com")
                .add("CSeq", "1 REFER")
                .add("Refer-To", "<" + uri(protocol, "carol") + ">");
        if (!referSub) {
            headers.add("Refer-Sub", "false");
        }
        return new SipRequest(
                SipMethod.REFER,
                uri(protocol, "bob"),
                SipVersion.SIP_2_0,
                headers.build(),
                SipBody.empty()
        );
    }

    private static DialogLifecycleListener lifecycle(CompletableFuture<Throwable> failure) {
        return new DialogLifecycleListener() {
            @Override public void onManagerFailure(Throwable cause) { failure.complete(cause); }
        };
    }

    private static SubscriptionRequestProfile requestProfile(TransportEndpoint endpoint) {
        TransportProtocol protocol = endpoint.protocol();
        String host = endpoint.address().getAddress() == null
                ? endpoint.address().getHostString()
                : endpoint.address().getAddress().getHostAddress();
        return new SubscriptionRequestProfile(
                ViaTransport.of(protocol.name()), new SentBy(host, endpoint.address().getPort()),
                protocol, protocol == TransportProtocol.UDP
        );
    }

    private static SipUri uri(TransportProtocol protocol, String user) {
        return SipUri.parse((protocol == TransportProtocol.TLS ? "sips:" : "sip:") + user + "@example.com");
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
            case UDP -> new NettyUdpTransport(new UdpTransportConfig(
                    new InetSocketAddress(InetAddress.getLoopbackAddress(), 0)), inbound);
            case TCP -> new NettyTcpTransport(new TcpTransportConfig(
                    new InetSocketAddress(InetAddress.getLoopbackAddress(), 0)), inbound);
            case TLS -> new NettyTlsTransport(new TlsTransportConfig(
                    new InetSocketAddress(InetAddress.getLoopbackAddress(), 0),
                    org.loomsip.codec.StreamBufferLimits.DEFAULT,
                    org.loomsip.transport.ConnectionLimits.DEFAULT,
                    org.loomsip.transport.WriteQueueLimits.DEFAULT,
                    Objects.requireNonNull(serverContext, "serverContext"),
                    Objects.requireNonNull(clientContext, "clientContext"),
                    Duration.ofSeconds(5), true, "7g-refer-profile", List.of(), List.of()
            ), inbound);
        };
    }

    private static TransportEndpoint targetEndpoint(TransportProtocol protocol, TransportEndpoint endpoint) {
        return protocol == TransportProtocol.TLS
                ? TransportEndpoint.tls(new InetSocketAddress("localhost", endpoint.address().getPort()))
                : endpoint;
    }

    private static String sentBy(TransportEndpoint endpoint) {
        String host = endpoint.address().getAddress() == null
                ? endpoint.address().getHostString()
                : endpoint.address().getAddress().getHostAddress();
        return host + ':' + endpoint.address().getPort();
    }
}
