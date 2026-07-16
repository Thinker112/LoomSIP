package org.loomsip.transaction.noninvite;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.loomsip.codec.SipParseException;
import org.loomsip.message.SipHeaders;
import org.loomsip.message.SipMethod;
import org.loomsip.message.SipRequest;
import org.loomsip.message.SipResponse;
import org.loomsip.message.SipResponses;
import org.loomsip.message.SipUri;
import org.loomsip.transaction.timer.SipTimerConfig;
import org.loomsip.transport.InboundSipMessage;
import org.loomsip.transport.SipMessageHandler;
import org.loomsip.transport.TransportContext;
import org.loomsip.transport.TransportEndpoint;
import org.loomsip.transport.netty.NettyUdpTransport;
import org.loomsip.transport.netty.UdpTransportConfig;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Timeout(15)
class NonInviteTransactionUdpIntegrationTest {

    @Test
    void completesOptionsTransactionAcrossTwoNettyUdpTransports() throws Exception {
        DelegatingHandler clientHandler = new DelegatingHandler();
        DelegatingHandler serverHandler = new DelegatingHandler();
        NettyUdpTransport clientTransport = transport(clientHandler);
        NettyUdpTransport serverTransport = transport(serverHandler);
        CompletableFuture<SipResponse> receivedResponse = new CompletableFuture<>();
        CompletableFuture<Throwable> layerFailure = new CompletableFuture<>();
        AtomicInteger serverRequests = new AtomicInteger();

        NonInviteClientListener clientListener = new NonInviteClientListener() {
            @Override
            public void onResponse(
                    ClientTransactionHandle transaction,
                    SipResponse response,
                    TransportContext context
            ) {
                receivedResponse.complete(response);
            }

            @Override
            public void onLayerError(Throwable cause) {
                layerFailure.complete(cause);
            }
        };
        NonInviteServerListener serverListener = new NonInviteServerListener() {
            @Override
            public void onRequest(
                    ServerTransactionHandle transaction,
                    SipRequest request,
                    TransportContext context
            ) {
                serverRequests.incrementAndGet();
                transaction.sendResponse(SipResponses.createResponse(request, 200, "OK", "udp-server"));
            }

            @Override
            public void onLayerError(Throwable cause) {
                layerFailure.complete(cause);
            }
        };
        NonInviteServerListener ignoredServer = (transaction, request, context) -> {
        };
        NonInviteClientListener ignoredClient = (transaction, response, context) -> {
        };

        try (NonInviteTransactionManager clientManager = new NonInviteTransactionManager(
                clientTransport::send,
                SipTimerConfig.DEFAULT,
                NonInviteTransactionConfig.DEFAULT,
                clientListener,
                ignoredServer
        ); NonInviteTransactionManager serverManager = new NonInviteTransactionManager(
                serverTransport::send,
                SipTimerConfig.DEFAULT,
                NonInviteTransactionConfig.DEFAULT,
                ignoredClient,
                serverListener
        )) {
            clientHandler.delegateTo(clientManager);
            serverHandler.delegateTo(serverManager);
            serverTransport.start();
            clientTransport.start();

            ClientTransactionHandle transaction = clientManager.sendRequest(
                    optionsRequest(clientTransport.localEndpoint()),
                    serverTransport.localEndpoint()
            );
            SipResponse response = receivedResponse.get(5, TimeUnit.SECONDS);

            assertEquals(200, response.statusCode());
            assertEquals("OK", response.reasonPhrase());
            assertEquals(1, serverRequests.get());
            assertEquals(NonInviteClientState.COMPLETED, transaction.state());
            assertTrue(!layerFailure.isDone(), () -> "unexpected layer failure: " + layerFailure.join());
        } finally {
            clientTransport.close();
            serverTransport.close();
        }
    }

    private static NettyUdpTransport transport(SipMessageHandler handler) {
        return new NettyUdpTransport(
                new UdpTransportConfig(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0)),
                handler
        );
    }

    private static SipRequest optionsRequest(TransportEndpoint local) {
        String host = local.address().getAddress().getHostAddress();
        SipHeaders headers = SipHeaders.builder()
                .add("Via", "SIP/2.0/UDP " + host + ":" + local.address().getPort()
                        + ";branch=z9hG4bK-real-udp;rport")
                .add("Max-Forwards", "70")
                .add("From", "<sip:alice@example.com>;tag=udp-client")
                .add("To", "<sip:bob@example.com>")
                .add("Call-ID", "real-udp@example.com")
                .add("CSeq", "1 OPTIONS")
                .build();
        return new SipRequest(SipMethod.OPTIONS, SipUri.parse("sip:bob@example.com"), headers);
    }

    private static final class DelegatingHandler implements SipMessageHandler {

        private volatile SipMessageHandler delegate;

        private void delegateTo(SipMessageHandler delegate) {
            this.delegate = Objects.requireNonNull(delegate, "delegate");
        }

        @Override
        public void onMessage(InboundSipMessage message) {
            requireDelegate().onMessage(message);
        }

        @Override
        public void onMalformedMessage(TransportContext context, SipParseException cause) {
            requireDelegate().onMalformedMessage(context, cause);
        }

        @Override
        public void onTransportError(Throwable cause) {
            requireDelegate().onTransportError(cause);
        }

        private SipMessageHandler requireDelegate() {
            return Objects.requireNonNull(delegate, "SIP message handler delegate is not configured");
        }
    }
}
