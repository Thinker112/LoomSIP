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
import org.loomsip.message.header.EventHeaderValue;
import org.loomsip.message.header.ExpiresHeaderValue;
import org.loomsip.message.header.SentBy;
import org.loomsip.message.header.SipHeaderValues;
import org.loomsip.message.header.SubscriptionState;
import org.loomsip.message.header.SubscriptionStateHeaderValue;
import org.loomsip.message.header.ViaTransport;
import org.loomsip.subscription.InitialSubscriptionRequest;
import org.loomsip.subscription.SubscriptionClient;
import org.loomsip.subscription.SubscriptionConfig;
import org.loomsip.subscription.SubscriptionHandle;
import org.loomsip.subscription.SubscriptionId;
import org.loomsip.subscription.SubscriptionLifecycleState;
import org.loomsip.subscription.SubscriptionManager;
import org.loomsip.subscription.SubscriptionNotifyRouter;
import org.loomsip.subscription.SubscriptionNotifyServerListener;
import org.loomsip.subscription.SubscriptionPublisher;
import org.loomsip.subscription.SubscriptionRequestProfile;
import org.loomsip.subscription.SubscriptionRefreshRequest;
import org.loomsip.subscription.SubscriptionSubscribeClientListener;
import org.loomsip.subscription.SubscriptionSubscribeResponseRouter;
import org.loomsip.subscription.SubscriptionTerminationReason;
import org.loomsip.testkit.ScenarioEndpoint;
import org.loomsip.testkit.ScenarioInboundHandler;
import org.loomsip.transaction.ConnectionAwareMessageSender;
import org.loomsip.transaction.noninvite.NonInviteClientListener;
import org.loomsip.transaction.noninvite.NonInviteServerListener;
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
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * 7G real-transport acceptance for a generic RFC 3265 UAC Subscription.
 *
 * <pre>{@code
 * SUBSCRIBE --> 202 --> active NOTIFY --> terminated NOTIFY --> UAC cleanup
 * }</pre>
 */
@Timeout(30)
class SubscriptionTransportScenarioIntegrationTest {

    @Test
    void completesUacSubscriptionOverUdp() throws Exception {
        runScenario(TransportProtocol.UDP, null, null, RefreshOutcome.REMOTE_TERMINATION);
    }

    @Test
    void completesUacSubscriptionOverTcp() throws Exception {
        runScenario(TransportProtocol.TCP, null, null, RefreshOutcome.REMOTE_TERMINATION);
    }

    @Test
    void completesUacSubscriptionOverTls() throws Exception {
        try (TestTlsMaterial material = TestTlsMaterial.create("localhost")) {
            runScenario(TransportProtocol.TLS, material.serverContext(), material.trustedClientContext(), RefreshOutcome.REMOTE_TERMINATION);
        }
    }

    @Test
    void cancelsUacSubscriptionWithExpiresZeroOverUdp() throws Exception {
        runScenario(TransportProtocol.UDP, null, null, RefreshOutcome.LOCAL_CANCEL);
    }

    @Test
    void cancelsUacSubscriptionWithExpiresZeroOverTcp() throws Exception {
        runScenario(TransportProtocol.TCP, null, null, RefreshOutcome.LOCAL_CANCEL);
    }

    @Test
    void cancelsUacSubscriptionWithExpiresZeroOverTls() throws Exception {
        try (TestTlsMaterial material = TestTlsMaterial.create("localhost")) {
            runScenario(TransportProtocol.TLS, material.serverContext(), material.trustedClientContext(), RefreshOutcome.LOCAL_CANCEL);
        }
    }

    @Test
    void replacesUacRefreshDeadlineOverUdp() throws Exception {
        runScenario(TransportProtocol.UDP, null, null, RefreshOutcome.EXPIRY_REPLACED);
    }

    @Test
    void rejectsLateNotifyAfterUacSubscriptionManagerCloseOverUdp() throws Exception {
        runScenario(TransportProtocol.UDP, null, null, RefreshOutcome.MANAGER_CLOSED);
    }

    private static void runScenario(
            TransportProtocol protocol,
            SslContext serverContext,
            SslContext clientContext,
            RefreshOutcome outcome
    )
            throws Exception {
        ScenarioInboundHandler clientInbound = new ScenarioInboundHandler();
        ScenarioInboundHandler serverInbound = new ScenarioInboundHandler();
        SipTransport clientTransport = transport(protocol, clientInbound, serverContext, clientContext);
        SipTransport serverTransport = transport(protocol, serverInbound, serverContext, clientContext);
        TransportRegistry clientRegistry = registry(protocol, clientTransport);
        TransportRegistry serverRegistry = registry(protocol, serverTransport);
        VirtualSipScheduler subscriptionScheduler = new VirtualSipScheduler();
        SubscriptionManager clientSubscriptions = new SubscriptionManager(
                SubscriptionConfig.DEFAULT, Runnable::run, failure -> { throw new AssertionError(failure); }
                , subscriptionScheduler
        );
        CompletableFuture<Throwable> failure = new CompletableFuture<>();
        CompletableFuture<SipResponse> subscribed = new CompletableFuture<>();
        BlockingQueue<SipResponse> subscribeResponses = new LinkedBlockingQueue<>();
        BlockingQueue<SipResponse> notifyResponses = new LinkedBlockingQueue<>();
        AtomicReference<SipRequest> receivedSubscribe = new AtomicReference<>();
        AtomicReference<ScenarioEndpoint> serverReference = new AtomicReference<>();
        AtomicInteger branches = new AtomicInteger();
        ScenarioEndpoint client = null;
        ScenarioEndpoint server = null;
        try {
            clientRegistry.start();
            serverRegistry.start();
            TransportEndpoint clientTarget = targetEndpoint(protocol, clientTransport.localEndpoint());
            TransportEndpoint serverTarget = targetEndpoint(protocol, serverTransport.localEndpoint());
            SubscriptionSubscribeClientListener subscribeClient = new SubscriptionSubscribeClientListener(
                    new SubscriptionSubscribeResponseRouter(clientSubscriptions),
                    responseListener(subscribed, subscribeResponses, failure), Runnable::run, failure::complete
            );
            SubscriptionNotifyServerListener notifyServer = new SubscriptionNotifyServerListener(
                    new SubscriptionNotifyRouter(clientSubscriptions),
                    (transaction, request, context) -> transaction.sendResponse(SipResponses.createResponse(request, 405, "Method Not Allowed")),
                    Runnable::run, failure::complete
            );
            client = ScenarioEndpoint.create(
                    clientTransport,
                    new ConnectionAwareMessageSender(new TransportSelector(clientRegistry)),
                    serverTarget,
                    SipTimerConfig.DEFAULT,
                    lifecycle(failure),
                    (transaction, response, context) -> { },
                    (transaction, request, context) -> { },
                    subscribeClient,
                    notifyServer,
                    null
            );
            NonInviteServerListener subscribeServer = new NonInviteServerListener() {
                @Override
                public void onRequest(
                        org.loomsip.transaction.noninvite.ServerTransactionHandle transaction,
                        SipRequest request,
                        TransportContext context
                ) {
                    try {
                        if (!SipMethod.SUBSCRIBE.equals(request.method())) {
                            transaction.sendResponse(SipResponses.createResponse(request, 405, "Method Not Allowed"));
                            return;
                        }
                        receivedSubscribe.set(request);
                        int expires = SipHeaderValues.expires(request.headers()).seconds();
                        int status = request.headers().firstValue("To").orElseThrow().contains("tag=") ? 200 : 202;
                        SipResponse base = SipResponses.createResponse(request, status, status == 200 ? "OK" : "Accepted", "server-tag");
                        transaction.sendResponse(new SipResponse(base.version(), base.statusCode(), base.reasonPhrase(),
                                base.headers().toBuilder().add("Expires", Integer.toString(expires)).build(), base.body()));
                    } catch (Throwable cause) {
                        failure.complete(cause);
                    }
                }

                @Override public void onLayerError(Throwable cause) { failure.complete(cause); }
            };
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
                        ) {
                            if (SipMethod.NOTIFY.equals(transaction.originalRequest().method())) {
                                notifyResponses.add(response);
                            }
                        }
                        @Override public void onLayerError(Throwable cause) { failure.complete(cause); }
                    },
                    subscribeServer,
                    null
            );
            serverReference.set(server);
            clientInbound.bind(client.dispatcher());
            serverInbound.bind(server.dispatcher());

            SubscriptionClient subscriptionClient = new SubscriptionClient(
                    client.nonInviteTransactions(), requestProfile(clientTransport.localEndpoint()),
                    () -> "z9hG4bK-7g-subscribe-" + branches.incrementAndGet()
            );
            subscriptionClient.subscribe(new InitialSubscriptionRequest(
                    uri(protocol, "bob"), uri(protocol, "alice"), uri(protocol, "bob"),
                    "7g-subscription@example.com", "client-tag", 1,
                    new EventHeaderValue("presence", Optional.empty()), new ExpiresHeaderValue(120),
                    SipHeaders.empty(), SipBody.empty(), serverTarget
            ));

            assertEquals(202, subscribed.get(5, TimeUnit.SECONDS).statusCode());
            subscribeResponses.poll(5, TimeUnit.SECONDS);
            SipRequest subscribe = Objects.requireNonNull(receivedSubscribe.get(), "received SUBSCRIBE");
            SubscriptionId uacId = SubscriptionId.fromSubscribeResponse(subscribe, subscribed.getNow(null));
            SubscriptionHandle handle = clientSubscriptions.find(uacId).orElseThrow();
            SubscriptionId uasId = SubscriptionId.fromIncomingSubscribe(subscribe, "server-tag");
            ScenarioEndpoint serverEndpoint = Objects.requireNonNull(serverReference.get(), "server endpoint");
            SubscriptionPublisher publisher = new SubscriptionPublisher(serverEndpoint.nonInviteTransactions(),
                    requestProfile(serverTransport.localEndpoint()), () -> "z9hG4bK-7g-notify-" + branches.incrementAndGet());
            publish(publisher, uasId, protocol, clientTarget, 1, SubscriptionState.ACTIVE);

            assertEquals(200, notifyResponses.poll(5, TimeUnit.SECONDS).statusCode());
            awaitState(handle, SubscriptionLifecycleState.ACTIVE);
            switch (outcome) {
                case LOCAL_CANCEL -> {
                    refresh(subscriptionClient, uacId, protocol, serverTarget, 2, 0);
                    assertEquals(200, subscribeResponses.poll(5, TimeUnit.SECONDS).statusCode());
                }
                case EXPIRY_REPLACED -> {
                    refresh(subscriptionClient, uacId, protocol, serverTarget, 2, 30);
                    assertEquals(200, subscribeResponses.poll(5, TimeUnit.SECONDS).statusCode());
                    refresh(subscriptionClient, uacId, protocol, serverTarget, 3, 60);
                    assertEquals(200, subscribeResponses.poll(5, TimeUnit.SECONDS).statusCode());
                    subscriptionScheduler.advanceBy(Duration.ofSeconds(30));
                    assertEquals(SubscriptionLifecycleState.ACTIVE, handle.snapshot().state());
                    subscriptionScheduler.advanceBy(Duration.ofSeconds(30));
                }
                case REMOTE_TERMINATION -> {
                    publish(publisher, uasId, protocol, clientTarget, 2, SubscriptionState.TERMINATED);
                    assertEquals(200, notifyResponses.poll(5, TimeUnit.SECONDS).statusCode());
                }
                case MANAGER_CLOSED -> {
                    clientSubscriptions.close();
                    handle.terminated().toCompletableFuture().get(5, TimeUnit.SECONDS);
                    publish(publisher, uasId, protocol, clientTarget, 2, SubscriptionState.TERMINATED);
                    assertEquals(481, notifyResponses.poll(5, TimeUnit.SECONDS).statusCode());
                }
            }
            handle.terminated().toCompletableFuture().get(5, TimeUnit.SECONDS);

            assertEquals(SubscriptionLifecycleState.TERMINATED, handle.snapshot().state());
            assertEquals(Optional.of(switch (outcome) {
                case LOCAL_CANCEL -> SubscriptionTerminationReason.LOCAL_CANCELLED;
                case EXPIRY_REPLACED -> SubscriptionTerminationReason.EXPIRED;
                case REMOTE_TERMINATION -> SubscriptionTerminationReason.REMOTE_TERMINATED;
                case MANAGER_CLOSED -> SubscriptionTerminationReason.MANAGER_CLOSED;
            }), handle.snapshot().terminationReason());
            assertEquals(0, clientSubscriptions.size());
            assertFalse(failure.isDone(), () -> "unexpected scenario failure: " + failure.join());
        } finally {
            clientSubscriptions.close();
            subscriptionScheduler.close();
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

    private static void publish(
            SubscriptionPublisher publisher,
            SubscriptionId id,
            TransportProtocol protocol,
            TransportEndpoint target,
            long cseq,
            SubscriptionState state
    ) throws Exception {
        publisher.publish(new org.loomsip.subscription.SubscriptionNotification(
                id, uri(protocol, "alice"), uri(protocol, "bob"), uri(protocol, "alice"), cseq,
                new SubscriptionStateHeaderValue(state, Optional.empty(), Optional.empty(), Optional.empty()),
                SipHeaders.empty(), SipBody.empty(), target
        ));
    }

    private static void refresh(
            SubscriptionClient client,
            SubscriptionId id,
            TransportProtocol protocol,
            TransportEndpoint target,
            long cseq,
            int expires
    ) throws Exception {
        client.refresh(new SubscriptionRefreshRequest(
                id, uri(protocol, "bob"), uri(protocol, "alice"), uri(protocol, "bob"), cseq,
                new ExpiresHeaderValue(expires), SipHeaders.empty(), SipBody.empty(), target
        ));
    }

    private static NonInviteClientListener responseListener(
            CompletableFuture<SipResponse> subscribed,
            BlockingQueue<SipResponse> subscribeResponses,
            CompletableFuture<Throwable> failure
    ) {
        return new NonInviteClientListener() {
            @Override public void onResponse(
                    org.loomsip.transaction.noninvite.ClientTransactionHandle transaction,
                    SipResponse response,
                    TransportContext context
            ) {
                if (SipMethod.SUBSCRIBE.equals(transaction.originalRequest().method()) && response.statusCode() >= 200) {
                    subscribeResponses.add(response);
                    subscribed.complete(response);
                }
            }
            @Override public void onLayerError(Throwable cause) { failure.complete(cause); }
            @Override public void onTransportFailure(org.loomsip.transaction.noninvite.ClientTransactionHandle transaction, Throwable cause) { failure.complete(cause); }
        };
    }

    private static void awaitState(SubscriptionHandle handle, SubscriptionLifecycleState expected) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (handle.snapshot().state() == expected) {
                return;
            }
            Thread.sleep(10);
        }
        assertEquals(expected, handle.snapshot().state());
    }

    private static DialogLifecycleListener lifecycle(CompletableFuture<Throwable> failure) {
        return new DialogLifecycleListener() {
            @Override public void onManagerFailure(Throwable cause) { failure.complete(cause); }
        };
    }

    private static SubscriptionRequestProfile requestProfile(TransportEndpoint endpoint) {
        String host = endpoint.address().getAddress() == null
                ? endpoint.address().getHostString()
                : endpoint.address().getAddress().getHostAddress();
        return new SubscriptionRequestProfile(ViaTransport.of(endpoint.protocol().name()),
                new SentBy(host, endpoint.address().getPort()), endpoint.protocol(), endpoint.protocol() == TransportProtocol.UDP);
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
                    Duration.ofSeconds(5), true, "7g-subscription-profile", List.of(), List.of()
            ), inbound);
        };
    }

    private static TransportEndpoint targetEndpoint(TransportProtocol protocol, TransportEndpoint endpoint) {
        return protocol == TransportProtocol.TLS
                ? TransportEndpoint.tls(new InetSocketAddress("localhost", endpoint.address().getPort()))
                : endpoint;
    }

    private enum RefreshOutcome {
        REMOTE_TERMINATION,
        LOCAL_CANCEL,
        EXPIRY_REPLACED,
        MANAGER_CLOSED
    }
}
