package org.loomsip.transport.netty;

import io.netty.handler.ssl.SslContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
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
import org.loomsip.transport.SipMessageHandler;
import org.loomsip.transport.TlsHandshakeException;
import org.loomsip.transport.TlsPeerVerificationException;
import org.loomsip.transport.TransportEndpoint;
import org.loomsip.transport.TransportException;
import org.loomsip.transport.TransportState;

import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

@Timeout(20)
class NettyTlsTransportTest {

    @Test
    void completesTrustedRoundTripAndReusesTlsConnection() throws Exception {
        try (TestTlsMaterial material = TestTlsMaterial.create("localhost")) {
            SslContext serverContext = material.serverContext();
            SslContext clientContext = material.trustedClientContext();
            CompletableFuture<SipResponse> response = new CompletableFuture<>();
            AtomicReference<NettyTlsTransport> serverReference = new AtomicReference<>();
            NettyTlsTransport server = tlsTransport(serverContext, serverContext, message -> {
                SipRequest request = (SipRequest) message.message();
                serverReference.get().send(
                        SipResponses.createResponse(request, 200, "OK"),
                        TransportEndpoint.tls(message.context().remoteAddress())
                );
            });
            serverReference.set(server);
            NettyTlsTransport client = tlsTransport(serverContext, clientContext, message ->
                    response.complete((SipResponse) message.message())
            );

            try {
                server.start();
                client.start();
                TransportEndpoint target = tlsTarget(server.localEndpoint().address().getPort(), "localhost");

                client.send(optionsRequest(client.localEndpoint(), "tls-round-trip"), target)
                        .toCompletableFuture().get(5, TimeUnit.SECONDS);
                assertEquals(200, response.get(5, TimeUnit.SECONDS).statusCode());

                client.send(optionsRequest(client.localEndpoint(), "tls-reuse"), target)
                        .toCompletableFuture().get(5, TimeUnit.SECONDS);
                assertEquals(1, client.connectionManager().activeConnectionCount());
                assertEquals(1, server.connectionManager().activeConnectionCount());
                assertEquals(TransportState.RUNNING, server.state());
            } finally {
                client.close();
                server.close();
            }
        }
    }

    @Test
    void rejectsUntrustedCertificateAndHostnameMismatchWithoutTcpFallback() throws Exception {
        try (TestTlsMaterial serverMaterial = TestTlsMaterial.create("localhost");
             TestTlsMaterial otherMaterial = TestTlsMaterial.create("otherhost")) {
            SslContext serverContext = serverMaterial.serverContext();
            NettyTlsTransport server = tlsTransport(
                    serverContext,
                    serverContext,
                    message -> {
                    }
            );
            NettyTlsTransport untrustedClient = tlsTransport(
                    serverContext,
                    otherMaterial.trustedClientContext(),
                    message -> {
                    }
            );
            NettyTlsTransport mismatchClient = tlsTransport(
                    serverContext,
                    serverMaterial.trustedClientContext(),
                    message -> {
                    }
            );

            try {
                server.start();
                untrustedClient.start();
                mismatchClient.start();
                int port = server.localEndpoint().address().getPort();

                ExecutionException untrusted = assertThrows(ExecutionException.class, () ->
                        untrustedClient.send(
                                optionsRequest(untrustedClient.localEndpoint(), "untrusted"),
                                tlsTarget(port, "localhost")
                        ).toCompletableFuture().get(5, TimeUnit.SECONDS)
                );
                assertInstanceOf(TlsPeerVerificationException.class, untrusted.getCause());

                ExecutionException mismatch = assertThrows(ExecutionException.class, () ->
                        mismatchClient.send(
                                optionsRequest(mismatchClient.localEndpoint(), "mismatch"),
                                tlsTarget(port, "127.0.0.1")
                        ).toCompletableFuture().get(5, TimeUnit.SECONDS)
                );
                assertInstanceOf(TlsPeerVerificationException.class, mismatch.getCause());
                assertEquals(0, untrustedClient.connectionManager().activeConnectionCount());
                assertEquals(0, mismatchClient.connectionManager().activeConnectionCount());
            } finally {
                mismatchClient.close();
                untrustedClient.close();
                server.close();
            }
        }
    }

    @Test
    void failsHandshakeWhenPeerDoesNotSpeakTlsAndSendsNoSipPlaintext() throws Exception {
        CountDownLatch accepted = new CountDownLatch(1);
        CompletableFuture<byte[]> peerBytes = new CompletableFuture<>();
        try (ServerSocket peer = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            Thread.startVirtualThread(() -> {
                try (Socket socket = peer.accept()) {
                    accepted.countDown();
                    socket.setSoTimeout(500);
                    byte[] bytes = socket.getInputStream().readNBytes(64);
                    peerBytes.complete(bytes);
                } catch (Throwable cause) {
                    peerBytes.completeExceptionally(cause);
                }
            });
            try (TestTlsMaterial material = TestTlsMaterial.create("localhost")) {
                SslContext context = material.serverContext();
                NettyTlsTransport client = tlsTransport(
                        context,
                        material.trustedClientContext(),
                        message -> {
                        },
                        Duration.ofMillis(200)
                );
                try {
                    client.start();
                    ExecutionException failure = assertThrows(ExecutionException.class, () ->
                            client.send(
                                    optionsRequest(client.localEndpoint(), "handshake-timeout"),
                                    tlsTarget(peer.getLocalPort(), "localhost")
                            ).toCompletableFuture().get(5, TimeUnit.SECONDS)
                    );
                    assertInstanceOf(TlsHandshakeException.class, failure.getCause());
                    assertTrue(accepted.await(5, TimeUnit.SECONDS));
                    byte[] observed = peerBytes.get(5, TimeUnit.SECONDS);
                    String asText = new String(observed, StandardCharsets.US_ASCII);
                    assertTrue(!asText.contains("OPTIONS") && !asText.contains("SIP/2.0"));
                } finally {
                    client.close();
                }
            }
        }
    }

    @Test
    void closesIdleTlsConnectionWithoutStoppingListener() throws Exception {
        try (TestTlsMaterial material = TestTlsMaterial.create("localhost")) {
            SslContext context = material.serverContext();
            ConnectionLimits limits = new ConnectionLimits(
                    8,
                    4,
                    4,
                    Duration.ofSeconds(2),
                    Duration.ofMillis(150)
            );
            CompletableFuture<Throwable> serverError = new CompletableFuture<>();
            NettyTlsTransport server = tlsTransport(
                    context,
                    context,
                    new SipMessageHandler() {
                        @Override
                        public void onMessage(InboundSipMessage message) {
                        }

                        @Override
                        public void onTransportError(Throwable cause) {
                            serverError.complete(cause);
                        }
                    },
                    Duration.ofSeconds(2),
                    limits
            );
            NettyTlsTransport client = tlsTransport(
                    context,
                    material.trustedClientContext(),
                    message -> {
                    },
                    Duration.ofSeconds(2),
                    limits
            );
            try {
                server.start();
                client.start();
                client.send(
                        optionsRequest(client.localEndpoint(), "tls-idle"),
                        tlsTarget(server.localEndpoint().address().getPort(), "localhost")
                ).toCompletableFuture().get(5, TimeUnit.SECONDS);
                assertTrue(serverError.get(5, TimeUnit.SECONDS).getMessage().contains("idle timeout"));
                assertEquals(0, server.connectionManager().activeConnectionCount());
                assertEquals(TransportState.RUNNING, server.state());
            } finally {
                client.close();
                server.close();
            }
        }
    }

    private static NettyTlsTransport tlsTransport(
            SslContext serverContext,
            SslContext clientContext,
            SipMessageHandler handler
    ) {
        return tlsTransport(
                serverContext,
                clientContext,
                handler,
                Duration.ofSeconds(5),
                ConnectionLimits.DEFAULT
        );
    }

    private static NettyTlsTransport tlsTransport(
            SslContext serverContext,
            SslContext clientContext,
            SipMessageHandler handler,
            Duration handshakeTimeout
    ) {
        return tlsTransport(
                serverContext,
                clientContext,
                handler,
                handshakeTimeout,
                ConnectionLimits.DEFAULT
        );
    }

    private static NettyTlsTransport tlsTransport(
            SslContext serverContext,
            SslContext clientContext,
            SipMessageHandler handler,
            Duration handshakeTimeout,
            ConnectionLimits limits
    ) {
        return new NettyTlsTransport(
                new TlsTransportConfig(
                        loopbackAddress(),
                        org.loomsip.codec.StreamBufferLimits.DEFAULT,
                        limits,
                        serverContext,
                        clientContext,
                        handshakeTimeout,
                        true,
                        "test-profile",
                        java.util.List.of(),
                        java.util.List.of()
                ),
                handler
        );
    }

    private static TransportEndpoint tlsTarget(int port, String host) {
        return TransportEndpoint.tls(new InetSocketAddress(host, port));
    }

    private static InetSocketAddress loopbackAddress() {
        return new InetSocketAddress(InetAddress.getLoopbackAddress(), 0);
    }

    private static SipRequest optionsRequest(TransportEndpoint local, String id) {
        String host = local.address().getAddress().getHostAddress();
        SipHeaders headers = SipHeaders.builder()
                .add("Via", "SIP/2.0/TLS " + host + ":" + local.address().getPort()
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
