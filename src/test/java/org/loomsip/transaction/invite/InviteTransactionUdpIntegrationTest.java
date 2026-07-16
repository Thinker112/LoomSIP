package org.loomsip.transaction.invite;

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

import static org.junit.jupiter.api.Assertions.assertEquals;

@Timeout(15)
class InviteTransactionUdpIntegrationTest {

    @Test
    void completesInviteBusyAckFlowAcrossNettyUdpTransports() throws Exception {
        DelegatingHandler clientHandler = new DelegatingHandler();
        DelegatingHandler serverHandler = new DelegatingHandler();
        NettyUdpTransport clientTransport = transport(clientHandler);
        NettyUdpTransport serverTransport = transport(serverHandler);
        CompletableFuture<SipResponse> receivedResponse = new CompletableFuture<>();
        CompletableFuture<SipRequest> receivedAck = new CompletableFuture<>();

        InviteClientListener clientListener = new InviteClientListener() {
            @Override
            public void onResponse(
                    InviteClientHandle transaction,
                    SipResponse response,
                    TransportContext context
            ) {
                receivedResponse.complete(response);
            }
        };
        InviteServerListener serverListener = new InviteServerListener() {
            @Override
            public void onInvite(
                    InviteServerHandle transaction,
                    SipRequest invite,
                    TransportContext context
            ) {
                transaction.sendResponse(SipResponses.createResponse(
                        invite,
                        486,
                        "Busy Here",
                        "udp-server"
                ));
            }

            @Override
            public void onAck(
                    InviteServerHandle transaction,
                    SipRequest ack,
                    TransportContext context
            ) {
                receivedAck.complete(ack);
            }
        };
        InviteServerListener ignoredServer = (transaction, request, context) -> {
        };
        InviteClientListener ignoredClient = (transaction, response, context) -> {
        };

        try (InviteTransactionManager clientManager = new InviteTransactionManager(
                clientTransport::send,
                SipTimerConfig.DEFAULT,
                InviteTransactionConfig.DEFAULT,
                clientListener,
                ignoredServer
        ); InviteTransactionManager serverManager = new InviteTransactionManager(
                serverTransport::send,
                SipTimerConfig.DEFAULT,
                InviteTransactionConfig.DEFAULT,
                ignoredClient,
                serverListener
        )) {
            clientHandler.delegateTo(clientManager);
            serverHandler.delegateTo(serverManager);
            serverTransport.start();
            clientTransport.start();

            InviteClientHandle client = clientManager.sendInvite(
                    invite(clientTransport.localEndpoint()),
                    serverTransport.localEndpoint()
            );
            SipResponse response = receivedResponse.get(5, TimeUnit.SECONDS);
            SipRequest ack = receivedAck.get(5, TimeUnit.SECONDS);

            assertEquals(486, response.statusCode());
            assertEquals(SipMethod.ACK, ack.method());
            assertEquals("1 ACK", ack.headers().firstValue("CSeq").orElseThrow());
            assertEquals(InviteClientState.COMPLETED, client.state());
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

    private static SipRequest invite(TransportEndpoint local) {
        String host = local.address().getAddress().getHostAddress();
        SipHeaders headers = SipHeaders.builder()
                .add("Via", "SIP/2.0/UDP " + host + ":" + local.address().getPort()
                        + ";branch=z9hG4bK-real-invite;rport")
                .add("Max-Forwards", "70")
                .add("From", "<sip:alice@example.com>;tag=udp-client")
                .add("To", "<sip:bob@example.com>")
                .add("Call-ID", "real-invite@example.com")
                .add("CSeq", "1 INVITE")
                .build();
        return new SipRequest(SipMethod.INVITE, SipUri.parse("sip:bob@example.com"), headers);
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
