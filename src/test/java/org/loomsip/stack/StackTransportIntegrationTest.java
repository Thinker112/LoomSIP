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

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StackTransportIntegrationTest {

    @Test void udpStackRoutesClientRequestToTuHandler() throws Exception { roundTrip(TransportProtocol.UDP); }
    @Test void tcpStackRoutesClientRequestToTuHandler() throws Exception { roundTrip(TransportProtocol.TCP); }

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

    private static SipRequest options() {
        return new SipRequest(SipMethod.OPTIONS, SipUri.parse("sip:service@example.com"), SipHeaders.builder()
                .add("Via", "SIP/2.0/UDP client.example.com;branch=z9hG4bK-stack-e2e")
                .add("From", "<sip:client@example.com>;tag=caller").add("To", "<sip:service@example.com>")
                .add("Call-ID", "stack-e2e@example.com").add("CSeq", "1 OPTIONS").build());
    }
}
