package org.loomsip.transport.netty;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.loomsip.codec.SipParseException;
import org.loomsip.example.OptionsClient;
import org.loomsip.example.OptionsServer;
import org.loomsip.message.SipBody;
import org.loomsip.message.SipHeaders;
import org.loomsip.message.SipMethod;
import org.loomsip.message.SipRequest;
import org.loomsip.message.SipResponse;
import org.loomsip.message.SipUri;
import org.loomsip.message.SipVersion;
import org.loomsip.transport.InboundSipMessage;
import org.loomsip.transport.SendResult;
import org.loomsip.transport.SipMessageHandler;
import org.loomsip.transport.TransportContext;
import org.loomsip.transport.TransportEndpoint;
import org.loomsip.transport.TransportException;
import org.loomsip.transport.TransportState;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Timeout(15)
class NettyUdpTransportTest {

    @Test
    void sendsBinaryMessageWithContextOnVirtualThread() throws Exception {
        CompletableFuture<InboundSipMessage> received = new CompletableFuture<>();
        CompletableFuture<Boolean> callbackWasVirtual = new CompletableFuture<>();
        NettyUdpTransport receiver = transport(new SipMessageHandler() {
            @Override
            public void onMessage(InboundSipMessage message) {
                callbackWasVirtual.complete(Thread.currentThread().isVirtual());
                received.complete(message);
            }
        });
        NettyUdpTransport sender = transport(message -> {
        });

        try {
            receiver.start();
            sender.start();
            byte[] body = {0, 1, 2, 13, 10, (byte) 0xff};
            SipRequest request = optionsRequest(sender.localEndpoint(), body);

            SendResult sendResult = sender.send(request, receiver.localEndpoint())
                    .toCompletableFuture().get(5, TimeUnit.SECONDS);
            InboundSipMessage inbound = received.get(5, TimeUnit.SECONDS);

            assertTrue(callbackWasVirtual.get(5, TimeUnit.SECONDS));
            assertEquals(sender.localEndpoint(), sendResult.localEndpoint());
            assertEquals(receiver.localEndpoint(), sendResult.remoteEndpoint());
            assertEquals(receiver.localEndpoint().address(), inbound.context().localAddress());
            assertEquals(sender.localEndpoint().address(), inbound.context().remoteAddress());
            assertArrayEquals(body, inbound.message().body().bytes());
            assertEquals(TransportState.RUNNING, receiver.state());
        } finally {
            sender.close();
            receiver.close();
        }

        assertEquals(TransportState.CLOSED, sender.state());
        assertEquals(TransportState.CLOSED, receiver.state());
        sender.close();
        receiver.close();
    }

    @Test
    void malformedDatagramDoesNotPreventNextValidMessage() throws Exception {
        CompletableFuture<SipParseException> malformed = new CompletableFuture<>();
        CompletableFuture<InboundSipMessage> valid = new CompletableFuture<>();
        NettyUdpTransport receiver = transport(new SipMessageHandler() {
            @Override
            public void onMessage(InboundSipMessage message) {
                valid.complete(message);
            }

            @Override
            public void onMalformedMessage(TransportContext context, SipParseException cause) {
                malformed.complete(cause);
            }
        });
        NettyUdpTransport sender = transport(message -> {
        });

        try {
            receiver.start();
            sendRaw("BROKEN\r\n\r\n".getBytes(StandardCharsets.US_ASCII), receiver.localEndpoint().address());
            assertTrue(malformed.get(5, TimeUnit.SECONDS).getMessage().contains("start-line"));

            sender.start();
            sender.send(optionsRequest(sender.localEndpoint(), new byte[0]), receiver.localEndpoint())
                    .toCompletableFuture().get(5, TimeUnit.SECONDS);

            assertInstanceOf(SipRequest.class, valid.get(5, TimeUnit.SECONDS).message());
            assertEquals(TransportState.RUNNING, receiver.state());
        } finally {
            sender.close();
            receiver.close();
        }
    }

    @Test
    void rejectsOversizedInboundAndOutboundDatagrams() throws Exception {
        CompletableFuture<SipParseException> malformed = new CompletableFuture<>();
        UdpTransportConfig smallConfig = new UdpTransportConfig(loopbackAddress(), 64);
        NettyUdpTransport transport = new NettyUdpTransport(smallConfig, new SipMessageHandler() {
            @Override
            public void onMessage(InboundSipMessage message) {
            }

            @Override
            public void onMalformedMessage(TransportContext context, SipParseException cause) {
                malformed.complete(cause);
            }
        });

        try {
            transport.start();
            sendRaw(new byte[65], transport.localEndpoint().address());
            assertTrue(malformed.get(5, TimeUnit.SECONDS).getMessage().contains("datagram exceeds"));

            ExecutionException failure = assertThrows(ExecutionException.class, () -> transport.send(
                    optionsRequest(transport.localEndpoint(), new byte[0]),
                    new TransportEndpoint(
                            transport.localEndpoint().protocol(),
                            new InetSocketAddress("127.0.0.1", 5060)
                    )
            ).toCompletableFuture().get(5, TimeUnit.SECONDS));
            assertInstanceOf(TransportException.class, failure.getCause());
            assertTrue(failure.getCause().getMessage().contains("datagram limit"));
        } finally {
            transport.close();
        }
    }

    @Test
    void enforcesLifecycleAndReportsHandlerFailure() throws Exception {
        CompletableFuture<Throwable> callbackFailure = new CompletableFuture<>();
        NettyUdpTransport receiver = transport(new SipMessageHandler() {
            @Override
            public void onMessage(InboundSipMessage message) {
                throw new IllegalStateException("handler failed");
            }

            @Override
            public void onTransportError(Throwable cause) {
                callbackFailure.complete(cause);
            }
        });
        NettyUdpTransport sender = transport(message -> {
        });

        assertEquals(TransportState.NEW, receiver.state());
        assertThrows(IllegalStateException.class, receiver::localEndpoint);

        try {
            receiver.start();
            sender.start();
            assertThrows(TransportException.class, receiver::start);
            sender.send(optionsRequest(sender.localEndpoint(), new byte[0]), receiver.localEndpoint())
                    .toCompletableFuture().get(5, TimeUnit.SECONDS);

            assertEquals("handler failed", callbackFailure.get(5, TimeUnit.SECONDS).getMessage());
            assertEquals(TransportState.RUNNING, receiver.state());
        } finally {
            sender.close();
            receiver.close();
        }

        ExecutionException closedSend = assertThrows(ExecutionException.class, () -> receiver.send(
                optionsRequest(sender.localEndpoint(), new byte[0]),
                sender.localEndpoint()
        ).toCompletableFuture().get(5, TimeUnit.SECONDS));
        assertInstanceOf(TransportException.class, closedSend.getCause());
    }

    @Test
    void completesOptionsRoundTripExample() throws Exception {
        InetSocketAddress bindAddress = loopbackAddress();
        try (OptionsServer server = new OptionsServer(bindAddress);
             OptionsClient client = new OptionsClient(bindAddress)) {
            server.start();
            client.start();
            assertNotEquals(0, server.localEndpoint().address().getPort());
            assertNotEquals(0, client.localEndpoint().address().getPort());

            SipResponse response = client.sendOptions(server.localEndpoint())
                    .toCompletableFuture().get(5, TimeUnit.SECONDS);

            assertEquals(200, response.statusCode());
            assertEquals("OK", response.reasonPhrase());
            assertTrue(response.headers().firstValue("To").orElseThrow().contains(";tag="));
            assertEquals("1 OPTIONS", response.headers().firstValue("CSeq").orElseThrow());
        }
    }

    private static NettyUdpTransport transport(SipMessageHandler handler) {
        return new NettyUdpTransport(new UdpTransportConfig(loopbackAddress()), handler);
    }

    private static InetSocketAddress loopbackAddress() {
        return new InetSocketAddress(InetAddress.getLoopbackAddress(), 0);
    }

    private static SipRequest optionsRequest(TransportEndpoint local, byte[] body) {
        String host = local.address().getAddress().getHostAddress();
        SipHeaders headers = SipHeaders.builder()
                .add("Via", "SIP/2.0/UDP " + host + ":" + local.address().getPort()
                        + ";branch=z9hG4bK-test;rport")
                .add("Max-Forwards", "70")
                .add("From", "<sip:alice@example.com>;tag=client-tag")
                .add("To", "<sip:bob@example.com>")
                .add("Call-ID", "call-test@example.com")
                .add("CSeq", "1 OPTIONS")
                .build();
        return new SipRequest(
                SipMethod.OPTIONS,
                SipUri.parse("sip:bob@example.com"),
                SipVersion.SIP_2_0,
                headers,
                SipBody.of(body)
        );
    }

    private static void sendRaw(byte[] bytes, InetSocketAddress target) throws Exception {
        try (DatagramSocket socket = new DatagramSocket()) {
            DatagramPacket packet = new DatagramPacket(bytes, bytes.length, target);
            socket.send(packet);
        }
    }
}
