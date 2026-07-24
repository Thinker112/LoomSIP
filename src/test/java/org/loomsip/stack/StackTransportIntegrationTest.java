package org.loomsip.stack;

import org.junit.jupiter.api.Test;
import org.loomsip.message.SipHeaders;
import org.loomsip.message.SipMethod;
import org.loomsip.message.SipRequest;
import org.loomsip.message.SipResponses;
import org.loomsip.message.SipUri;
import org.loomsip.transport.TransportEndpoint;
import org.loomsip.transport.TransportProtocol;
import org.loomsip.transport.netty.NettyTransports;
import org.loomsip.transport.netty.TcpTransportConfig;
import org.loomsip.transport.netty.UdpTransportConfig;
import org.loomsip.message.header.EventHeaderValue;
import org.loomsip.subscription.SubscriptionAcceptance;
import org.loomsip.subscription.SubscriptionLifecycleState;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.net.ServerSocket;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class StackTransportIntegrationTest {

    @Test void udpStackRoutesClientRequestToTuHandler() throws Exception { roundTrip(TransportProtocol.UDP); }
    @Test void tcpStackRoutesClientRequestToTuHandler() throws Exception { roundTrip(TransportProtocol.TCP); }

    @Test void udpStackCreatesConfirmedDialogFromInvite2xx() throws Exception {
        int serverPort = availablePort(), clientPort = availablePort();
        TransportEndpoint target = TransportEndpoint.udp(new InetSocketAddress(InetAddress.getLoopbackAddress(), serverPort));
        CountDownLatch infoHandled = new CountDownLatch(1);
        SipStackApplication serverApp = SipStackApplication.builder().inviteHandler(context -> {
            var base = SipResponses.createResponse(context.request(), 200, "OK", "uas");
            context.respond(new org.loomsip.message.SipResponse(base.version(), 200, "OK",
                    base.headers().toBuilder().add("Contact", "<sip:service@127.0.0.1:" + serverPort + ">").build(), base.body()));
        }).requestHandler(context -> context.respond(SipResponses.createResponse(context.request(), 200, "OK")))
                .infoPackage("stack-info", request -> { infoHandled.countDown(); return CompletableFuture.completedFuture(org.loomsip.info.InfoResponse.ok()); }).build();
        try (LoomSipStack server = dialogStack(TransportProtocol.UDP, serverPort, serverApp, target);
             LoomSipStack client = dialogStack(TransportProtocol.UDP, clientPort, SipStackApplication.builder().build(), target)) {
            server.start().toCompletableFuture().join(); client.start().toCompletableFuture().join();
            client.client().invite(new InviteRequest(invite(), target));
            assertTrue(await(() -> client.dialogs().orElseThrow().activeDialogs() == 1));
            assertEquals(org.loomsip.dialog.DialogState.CONFIRMED,
                    client.dialogs().orElseThrow().find(new org.loomsip.dialog.DialogId("stack-invite@example.com", "caller", "uas"))
                            .orElseThrow().snapshot().state());
            var dialog = client.dialogs().orElseThrow().find(new org.loomsip.dialog.DialogId("stack-invite@example.com", "caller", "uas"))
                    .orElseThrow();
            dialog.sendInfo(new org.loomsip.info.InfoRequest(
                            new org.loomsip.message.header.InfoPackageHeaderValue("stack-info"), SipHeaders.empty(), org.loomsip.message.SipBody.empty()
                    )).toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertTrue(infoHandled.await(5, TimeUnit.SECONDS));
            dialog.sendBye().toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertTrue(await(() -> client.dialogs().orElseThrow().activeDialogs() == 0));
            assertThrows(java.util.concurrent.ExecutionException.class, () -> dialog.sendInfo(new org.loomsip.info.InfoRequest(
                    new org.loomsip.message.header.InfoPackageHeaderValue("stack-info"), SipHeaders.empty(), org.loomsip.message.SipBody.empty()
            )).toCompletableFuture().get(5, TimeUnit.SECONDS));
            assertEquals(0, client.dialogs().orElseThrow().activeDialogs());
        }
    }

    @Test void tcpStackCreatesConfirmedDialogFromInvite2xx() throws Exception {
        int serverPort = availablePort(), clientPort = availablePort();
        TransportEndpoint target = TransportEndpoint.tcp(new InetSocketAddress(InetAddress.getLoopbackAddress(), serverPort));
        SipStackApplication serverApp = SipStackApplication.builder().inviteHandler(context -> {
            var base = SipResponses.createResponse(context.request(), 200, "OK", "uas");
            context.respond(new org.loomsip.message.SipResponse(base.version(), 200, "OK",
                    base.headers().toBuilder().add("Contact", "<sip:service@127.0.0.1:" + serverPort + ">").build(), base.body()));
        }).build();
        try (LoomSipStack server = dialogStack(TransportProtocol.TCP, serverPort, serverApp, target);
             LoomSipStack client = dialogStack(TransportProtocol.TCP, clientPort, SipStackApplication.builder().build(), target)) {
            server.start().toCompletableFuture().join(); client.start().toCompletableFuture().join();
            client.client().invite(new InviteRequest(invite(), target));
            assertTrue(await(() -> client.dialogs().orElseThrow().activeDialogs() == 1));
        }
    }


    @Test void udpStackRoutesSubscribeToRegisteredEventPackage() throws Exception { subscribeRoundTrip(TransportProtocol.UDP); }
    @Test void tcpStackRoutesSubscribeToRegisteredEventPackage() throws Exception { subscribeRoundTrip(TransportProtocol.TCP); }

    private static void subscribeRoundTrip(TransportProtocol protocol) throws Exception {
        CountDownLatch accepted = new CountDownLatch(1);
        EventHeaderValue event = new EventHeaderValue("stack-test", Optional.empty());
        SipStackApplication serverApp = SipStackApplication.builder().subscriptionPackage(event, request -> {
            accepted.countDown(); return CompletableFuture.completedFuture(new SubscriptionAcceptance(200, "OK", 30));
        }).build();
        SipStackApplication clientApp = SipStackApplication.builder().build();
        try (LoomSipStack server = stack(protocol, serverApp); LoomSipStack client = stack(protocol, clientApp)) {
            server.start().toCompletableFuture().join(); client.start().toCompletableFuture().join();
            client.client().request(new OutgoingRequest(subscribe(), server.snapshot().transports().getFirst().localEndpoint().orElseThrow()));
            assertTrue(accepted.await(5, TimeUnit.SECONDS));
            assertTrue(server.subscriptions().isPresent());
            assertTrue(await(() -> client.subscriptions().orElseThrow().size() == 1));
            var id = client.subscriptions().orElseThrow().snapshots().getFirst().id();
            TransportEndpoint clientEndpoint = client.snapshot().transports().getFirst().localEndpoint().orElseThrow();
            server.client().request(new OutgoingRequest(notify(id), clientEndpoint));
            assertTrue(await(() -> client.subscriptions().orElseThrow().snapshots().getFirst().state()
                    == SubscriptionLifecycleState.ACTIVE));
            server.client().request(new OutgoingRequest(notify(id, "terminated;reason=deactivated"), clientEndpoint));
            assertTrue(await(() -> client.subscriptions().orElseThrow().size() == 0));
            server.client().request(new OutgoingRequest(notify(id, "active;expires=30"), clientEndpoint));
            Thread.sleep(50);
            assertEquals(0, client.subscriptions().orElseThrow().size());
        }
    }

    @Test void udpStackCreatesImplicitReferSubscription() throws Exception {
        CountDownLatch accepted = new CountDownLatch(1);
        CountDownLatch subscriptionCreated = new CountDownLatch(1);
        SipStackApplication serverApp = SipStackApplication.builder().referHandler(request -> {
            accepted.countDown(); return CompletableFuture.completedFuture(new org.loomsip.refer.ReferAcceptance(202, "Accepted"));
        }).referSubscriptionListener((request, subscription, context) -> subscriptionCreated.countDown()).build();
        try (LoomSipStack server = stack(TransportProtocol.UDP, serverApp); LoomSipStack client = stack(TransportProtocol.UDP, SipStackApplication.builder().build())) {
            server.start().toCompletableFuture().join(); client.start().toCompletableFuture().join();
            client.client().request(new OutgoingRequest(refer(), server.snapshot().transports().getFirst().localEndpoint().orElseThrow()));
            assertTrue(accepted.await(5, TimeUnit.SECONDS));
            assertTrue(await(() -> server.subscriptions().orElseThrow().size() == 1));
            assertTrue(subscriptionCreated.await(5, TimeUnit.SECONDS));
        }
    }

    private static void roundTrip(TransportProtocol protocol) throws Exception {
        CountDownLatch received = new CountDownLatch(1);
        TuHandlerRegistry handlers = TuHandlerRegistry.builder().requestHandler(context -> {
            context.respond(SipResponses.createResponse(context.request(), 200, "OK")); received.countDown();
        }).build();
        try (LoomSipStack server = stack(protocol, handlers); LoomSipStack client = stack(protocol, TuHandlerRegistry.builder().build())) {
            server.start().toCompletableFuture().join(); client.start().toCompletableFuture().join();
            TransportEndpoint target = server.snapshot().transports().getFirst().localEndpoint().orElseThrow();
            client.client().request(new OutgoingRequest(options(), target));
            assertTrue(received.await(5, TimeUnit.SECONDS));
            assertEquals(SipStackState.RUNNING, client.snapshot().state());
        }
    }

    private static LoomSipStack stack(TransportProtocol protocol, TuHandlerRegistry handlers) throws Exception {
        InetSocketAddress bind = new InetSocketAddress(InetAddress.getLoopbackAddress(), 0);
        return LoomSipStack.builder().handlers(handlers).transport(protocol, switch (protocol) {
            case UDP -> NettyTransports.udp(new UdpTransportConfig(bind));
            case TCP -> NettyTransports.tcp(new TcpTransportConfig(bind));
            case TLS -> throw new AssertionError();
        }).build();
    }

    private static LoomSipStack stack(TransportProtocol protocol, SipStackApplication application) throws Exception {
        InetSocketAddress bind = new InetSocketAddress(InetAddress.getLoopbackAddress(), 0);
        return LoomSipStack.builder().application(application).transport(protocol, switch (protocol) {
            case UDP -> NettyTransports.udp(new UdpTransportConfig(bind));
            case TCP -> NettyTransports.tcp(new TcpTransportConfig(bind));
            case TLS -> throw new AssertionError();
        }).build();
    }

    private static SipRequest options() {
        return new SipRequest(SipMethod.OPTIONS, SipUri.parse("sip:service@example.com"), SipHeaders.builder()
                .add("Via", "SIP/2.0/UDP client.example.com;branch=z9hG4bK-stack-e2e")
                .add("From", "<sip:client@example.com>;tag=caller").add("To", "<sip:service@example.com>")
                .add("Call-ID", "stack-e2e@example.com").add("CSeq", "1 OPTIONS").build());
    }

    private static SipRequest subscribe() {
        return new SipRequest(SipMethod.SUBSCRIBE, SipUri.parse("sip:service@example.com"), SipHeaders.builder()
                .add("Via", "SIP/2.0/UDP client.example.com;branch=z9hG4bK-stack-sub")
                .add("From", "<sip:client@example.com>;tag=caller").add("To", "<sip:service@example.com>")
                .add("Call-ID", "stack-sub@example.com").add("CSeq", "1 SUBSCRIBE")
                .add("Event", "stack-test").add("Expires", "30").build());
    }

    private static SipRequest refer() {
        return new SipRequest(SipMethod.REFER, SipUri.parse("sip:service@example.com"), SipHeaders.builder()
                .add("Via", "SIP/2.0/UDP client.example.com;branch=z9hG4bK-stack-refer")
                .add("From", "<sip:client@example.com>;tag=caller").add("To", "<sip:service@example.com>")
                .add("Call-ID", "stack-refer@example.com").add("CSeq", "1 REFER")
                .add("Refer-To", "<sip:target@example.com>").add("Refer-Sub", "true").build());
    }

    private static LoomSipStack dialogStack(TransportProtocol protocol, int port, SipStackApplication app, TransportEndpoint target) {
        var profile = protocol == TransportProtocol.UDP ? org.loomsip.dialog.DialogRequestProfile.udp(
                new org.loomsip.message.header.SentBy("127.0.0.1", port)) : new org.loomsip.dialog.DialogRequestProfile(
                org.loomsip.message.header.ViaTransport.TCP, new org.loomsip.message.header.SentBy("127.0.0.1", port), protocol, false);
        var config = new DialogStackConfig(profile,
                (uri, preferred) -> CompletableFuture.completedFuture(target), org.loomsip.dialog.DialogConfig.DEFAULT,
                new org.loomsip.dialog.DialogLifecycleListener() { });
        return LoomSipStack.builder().application(app).dialog(config).transport(protocol, protocol == TransportProtocol.UDP
                ? NettyTransports.udp(new UdpTransportConfig(new InetSocketAddress(InetAddress.getLoopbackAddress(), port)))
                : NettyTransports.tcp(new TcpTransportConfig(new InetSocketAddress(InetAddress.getLoopbackAddress(), port)))).build();
    }

    private static SipRequest invite() {
        return new SipRequest(SipMethod.INVITE, SipUri.parse("sip:service@example.com"), SipHeaders.builder()
                .add("Via", "SIP/2.0/UDP client.example.com;branch=z9hG4bK-stack-invite").add("From", "<sip:client@example.com>;tag=caller")
                .add("To", "<sip:service@example.com>").add("Call-ID", "stack-invite@example.com").add("CSeq", "1 INVITE")
                .add("Contact", "<sip:client@example.com>").build());
    }
    private static int availablePort() throws Exception { try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) { return socket.getLocalPort(); } }


    private static SipRequest notify(org.loomsip.subscription.SubscriptionId id) {
        return notify(id, "active;expires=30");
    }

    private static SipRequest notify(org.loomsip.subscription.SubscriptionId id, String state) {
        return new SipRequest(SipMethod.NOTIFY, SipUri.parse("sip:client@example.com"), SipHeaders.builder()
                .add("Via", "SIP/2.0/UDP server.example.com;branch=z9hG4bK-" + java.util.UUID.randomUUID())
                .add("From", "<sip:service@example.com>;tag=" + id.remoteTag())
                .add("To", "<sip:client@example.com>;tag=" + id.localTag())
                .add("Call-ID", id.callId()).add("CSeq", "2 NOTIFY")
                .add("Event", id.event().wireValue()).add("Subscription-State", state).build());
    }

    private static boolean await(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) return true;
            Thread.sleep(10);
        }
        return condition.getAsBoolean();
    }
}
