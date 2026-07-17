package org.loomsip.transport.netty;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.loomsip.codec.SipMessageEncoder;
import org.loomsip.codec.SipParseException;
import org.loomsip.message.SipBody;
import org.loomsip.message.SipHeaders;
import org.loomsip.message.SipMethod;
import org.loomsip.message.SipRequest;
import org.loomsip.message.SipResponse;
import org.loomsip.message.SipResponses;
import org.loomsip.message.SipUri;
import org.loomsip.message.SipVersion;
import org.loomsip.transport.ConnectionLimits;
import org.loomsip.transport.InboundSipMessage;
import org.loomsip.transport.SendResult;
import org.loomsip.transport.SipMessageHandler;
import org.loomsip.transport.TransportEndpoint;
import org.loomsip.transport.TransportException;
import org.loomsip.transport.TransportState;

import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Timeout(20)
class NettyTcpTransportTest {

    @Test
    void completesRoundTripOnOneConnectionWithVirtualThreadCallbacks() throws Exception {
        CompletableFuture<SipResponse> response = new CompletableFuture<>();
        AtomicBoolean serverCallbackWasVirtual = new AtomicBoolean();
        AtomicReference<NettyTcpTransport> serverReference = new AtomicReference<>();
        NettyTcpTransport server = transport(message -> {
            serverCallbackWasVirtual.set(Thread.currentThread().isVirtual());
            SipRequest request = (SipRequest) message.message();
            serverReference.get().send(
                    SipResponses.createResponse(request, 200, "OK"),
                    TransportEndpoint.tcp(message.context().remoteAddress())
            );
        });
        serverReference.set(server);
        NettyTcpTransport client = transport(message ->
                response.complete((SipResponse) message.message())
        );

        try {
            server.start();
            client.start();

            SendResult sent = client.send(
                    optionsRequest(client.localEndpoint(), "round-trip"),
                    server.localEndpoint()
            ).toCompletableFuture().get(5, TimeUnit.SECONDS);
            SipResponse received = response.get(5, TimeUnit.SECONDS);

            assertEquals(200, received.statusCode());
            assertEquals(server.localEndpoint(), sent.remoteEndpoint());
            assertTrue(serverCallbackWasVirtual.get());
            assertEquals(1, client.connectionManager().activeConnectionCount());
            assertEquals(1, server.connectionManager().activeConnectionCount());
        } finally {
            client.close();
            server.close();
        }

        assertEquals(0, client.connectionManager().activeConnectionCount());
        assertEquals(0, server.connectionManager().activeConnectionCount());
        assertEquals(TransportState.CLOSED, client.state());
        assertEquals(TransportState.CLOSED, server.state());
    }

    @Test
    void reusesOneConnectionForSequentialAndConcurrentSends() throws Exception {
        int messageCount = 24;
        CountDownLatch received = new CountDownLatch(messageCount);
        NettyTcpTransport server = transport(message -> received.countDown());
        NettyTcpTransport client = transport(message -> {
        });

        try {
            server.start();
            client.start();
            List<CompletableFuture<SendResult>> sends = new ArrayList<>();
            for (int index = 0; index < messageCount; index++) {
                sends.add(client.send(
                        optionsRequest(client.localEndpoint(), "concurrent-" + index),
                        server.localEndpoint()
                ).toCompletableFuture());
            }

            CompletableFuture.allOf(sends.toArray(CompletableFuture[]::new))
                    .get(5, TimeUnit.SECONDS);
            assertTrue(received.await(5, TimeUnit.SECONDS));
            assertEquals(1, client.connectionManager().activeConnectionCount());
            assertEquals(1, server.connectionManager().activeConnectionCount());
            assertEquals(0, client.connectionManager().pendingConnectCount());
        } finally {
            client.close();
            server.close();
        }
    }

    @Test
    void acceptsFragmentedAndStickyMessagesFromRawSocket() throws Exception {
        CountDownLatch received = new CountDownLatch(2);
        AtomicInteger messages = new AtomicInteger();
        NettyTcpTransport server = transport(message -> {
            messages.incrementAndGet();
            received.countDown();
        });

        try {
            server.start();
            byte[] first = new SipMessageEncoder().encode(
                    optionsRequest(server.localEndpoint(), "raw-first")
            );
            byte[] second = new SipMessageEncoder().encode(
                    optionsRequest(server.localEndpoint(), "raw-second")
            );
            int split = first.length / 2;

            try (Socket socket = new Socket()) {
                socket.connect(server.localEndpoint().address());
                OutputStream output = socket.getOutputStream();
                output.write(first, 0, split);
                output.flush();
                output.write(first, split, first.length - split);
                output.write(second);
                output.flush();

                assertTrue(received.await(5, TimeUnit.SECONDS));
                assertEquals(2, messages.get());
                assertEquals(1, server.connectionManager().activeConnectionCount());
            }
        } finally {
            server.close();
        }
    }

    @Test
    void reportsConnectionRefusalAndAllowsLaterRetry() throws Exception {
        NettyTcpTransport client = transport(message -> {
        });
        int unusedPort = unusedPort();
        TransportEndpoint refusedTarget = TransportEndpoint.tcp(
                new InetSocketAddress(InetAddress.getLoopbackAddress(), unusedPort)
        );

        try {
            client.start();
            ExecutionException firstFailure = assertThrows(ExecutionException.class, () ->
                    client.send(
                            optionsRequest(client.localEndpoint(), "refused-1"),
                            refusedTarget
                    ).toCompletableFuture().get(5, TimeUnit.SECONDS)
            );
            ExecutionException secondFailure = assertThrows(ExecutionException.class, () ->
                    client.send(
                            optionsRequest(client.localEndpoint(), "refused-2"),
                            refusedTarget
                    ).toCompletableFuture().get(5, TimeUnit.SECONDS)
            );

            assertInstanceOf(TransportException.class, firstFailure.getCause());
            assertInstanceOf(TransportException.class, secondFailure.getCause());
            assertEquals(0, client.connectionManager().activeConnectionCount());
            assertEquals(0, client.connectionManager().pendingConnectCount());
        } finally {
            client.close();
        }
    }

    @Test
    void removesConnectionAfterPeerReset() throws Exception {
        CompletableFuture<Throwable> connectionFailure = new CompletableFuture<>();
        CountDownLatch accepted = new CountDownLatch(1);
        CountDownLatch reset = new CountDownLatch(1);
        try (ServerSocket peer = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            Thread.startVirtualThread(() -> {
                try (Socket socket = peer.accept()) {
                    accepted.countDown();
                    reset.await();
                    socket.setSoLinger(true, 0);
                } catch (Throwable cause) {
                    connectionFailure.completeExceptionally(cause);
                }
            });
            NettyTcpTransport client = transport(new SipMessageHandler() {
                @Override
                public void onMessage(InboundSipMessage message) {
                }

                @Override
                public void onTransportError(Throwable cause) {
                    connectionFailure.complete(cause);
                }
            });

            try {
                client.start();
                TransportEndpoint target = TransportEndpoint.tcp(
                        new InetSocketAddress(InetAddress.getLoopbackAddress(), peer.getLocalPort())
                );
                client.send(
                        optionsRequest(client.localEndpoint(), "peer-reset"),
                        target
                ).toCompletableFuture().get(5, TimeUnit.SECONDS);
                assertTrue(accepted.await(5, TimeUnit.SECONDS));

                reset.countDown();
                String failureMessage = connectionFailure.get(5, TimeUnit.SECONDS)
                        .getMessage().toLowerCase(java.util.Locale.ROOT);
                assertTrue(failureMessage.contains("closed") || failureMessage.contains("reset"));
                assertEquals(0, client.connectionManager().activeConnectionCount());
                assertEquals(TransportState.RUNNING, client.state());
            } finally {
                reset.countDown();
                client.close();
            }
        }
    }

    @Test
    void malformedStreamClosesOnlyOffendingConnection() throws Exception {
        CompletableFuture<SipParseException> malformed = new CompletableFuture<>();
        CompletableFuture<InboundSipMessage> valid = new CompletableFuture<>();
        NettyTcpTransport server = transport(new SipMessageHandler() {
            @Override
            public void onMessage(InboundSipMessage message) {
                valid.complete(message);
            }

            @Override
            public void onMalformedMessage(
                    org.loomsip.transport.TransportContext context,
                    SipParseException cause
            ) {
                malformed.complete(cause);
            }
        });
        NettyTcpTransport client = transport(message -> {
        });

        try {
            server.start();
            try (Socket socket = new Socket()) {
                socket.connect(server.localEndpoint().address());
                socket.getOutputStream().write((
                        "OPTIONS sip:bob@example.com SIP/2.0\r\n"
                                + "Content-Length: invalid\r\n\r\n"
                ).getBytes(java.nio.charset.StandardCharsets.US_ASCII));
                socket.getOutputStream().flush();
                assertTrue(malformed.get(5, TimeUnit.SECONDS)
                        .getMessage().contains("Content-Length"));
            }

            client.start();
            client.send(
                    optionsRequest(client.localEndpoint(), "after-malformed"),
                    server.localEndpoint()
            ).toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertEquals(
                    "after-malformed@example.com",
                    valid.get(5, TimeUnit.SECONDS).message()
                            .headers().firstValue("Call-ID").orElseThrow()
            );
            assertEquals(TransportState.RUNNING, server.state());
        } finally {
            client.close();
            server.close();
        }
    }

    @Test
    void closesIdleConnectionsWithoutStoppingListener() throws Exception {
        CompletableFuture<Throwable> serverError = new CompletableFuture<>();
        ConnectionLimits limits = new ConnectionLimits(
                16,
                8,
                8,
                Duration.ofSeconds(2),
                Duration.ofMillis(150)
        );
        NettyTcpTransport server = transport(limits, new SipMessageHandler() {
            @Override
            public void onMessage(InboundSipMessage message) {
            }

            @Override
            public void onTransportError(Throwable cause) {
                serverError.complete(cause);
            }
        });
        NettyTcpTransport client = transport(limits, message -> {
        });

        try {
            server.start();
            client.start();
            client.send(
                    optionsRequest(client.localEndpoint(), "idle"),
                    server.localEndpoint()
            ).toCompletableFuture().get(5, TimeUnit.SECONDS);

            assertTrue(serverError.get(5, TimeUnit.SECONDS).getMessage().contains("idle timeout"));
            assertEquals(0, server.connectionManager().activeConnectionCount());
            assertEquals(TransportState.RUNNING, server.state());
        } finally {
            client.close();
            server.close();
        }
    }

    @Test
    void enforcesLifecycleAndProtocol() throws Exception {
        NettyTcpTransport transport = transport(message -> {
        });
        assertThrows(IllegalStateException.class, transport::localEndpoint);

        try {
            transport.start();
            assertThrows(TransportException.class, transport::start);
            ExecutionException protocolFailure = assertThrows(ExecutionException.class, () ->
                    transport.send(
                            optionsRequest(transport.localEndpoint(), "protocol"),
                            TransportEndpoint.udp(new InetSocketAddress(
                                    InetAddress.getLoopbackAddress(),
                                    5060
                            ))
                    ).toCompletableFuture().get(5, TimeUnit.SECONDS)
            );
            assertInstanceOf(TransportException.class, protocolFailure.getCause());
        } finally {
            transport.close();
        }

        ExecutionException closedFailure = assertThrows(ExecutionException.class, () ->
                transport.send(
                        optionsRequest(TransportEndpoint.tcp(new InetSocketAddress(
                                InetAddress.getLoopbackAddress(),
                                5060
                        )), "closed"),
                        TransportEndpoint.tcp(new InetSocketAddress(
                                InetAddress.getLoopbackAddress(),
                                5060
                        ))
                ).toCompletableFuture().get(5, TimeUnit.SECONDS)
        );
        assertInstanceOf(TransportException.class, closedFailure.getCause());
    }

    private static NettyTcpTransport transport(SipMessageHandler handler) {
        return new NettyTcpTransport(new TcpTransportConfig(loopbackAddress()), handler);
    }

    private static NettyTcpTransport transport(
            ConnectionLimits limits,
            SipMessageHandler handler
    ) {
        return new NettyTcpTransport(new TcpTransportConfig(loopbackAddress(), limits), handler);
    }

    private static InetSocketAddress loopbackAddress() {
        return new InetSocketAddress(InetAddress.getLoopbackAddress(), 0);
    }

    private static int unusedPort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return socket.getLocalPort();
        }
    }

    private static SipRequest optionsRequest(TransportEndpoint local, String id) {
        String host = local.address().getAddress().getHostAddress();
        SipHeaders headers = SipHeaders.builder()
                .add("Via", "SIP/2.0/TCP " + host + ":" + local.address().getPort()
                        + ";branch=z9hG4bK-" + id)
                .add("Max-Forwards", "70")
                .add("From", "<sip:alice@example.com>;tag=client-tag")
                .add("To", "<sip:bob@example.com>")
                .add("Call-ID", id + "@example.com")
                .add("CSeq", "1 OPTIONS")
                .build();
        return new SipRequest(
                SipMethod.OPTIONS,
                SipUri.parse("sip:bob@example.com"),
                SipVersion.SIP_2_0,
                headers,
                SipBody.empty()
        );
    }
}
