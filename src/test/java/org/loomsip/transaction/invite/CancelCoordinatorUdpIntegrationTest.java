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
import org.loomsip.transaction.SipTransactionDispatcher;
import org.loomsip.transaction.noninvite.NonInviteClientListener;
import org.loomsip.transaction.noninvite.NonInviteServerListener;
import org.loomsip.transaction.noninvite.NonInviteTransactionConfig;
import org.loomsip.transaction.noninvite.NonInviteTransactionManager;
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
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;

@Timeout(20)
class CancelCoordinatorUdpIntegrationTest {

    @Test
    void completesInviteCancelAndAckAcrossUnifiedUdpDispatchers() throws Exception {
        DelegatingHandler clientHandler = new DelegatingHandler();
        DelegatingHandler serverHandler = new DelegatingHandler();
        NettyUdpTransport clientTransport = transport(clientHandler);
        NettyUdpTransport serverTransport = transport(serverHandler);
        CompletableFuture<Void> provisionalReceived = new CompletableFuture<>();
        CompletableFuture<SipResponse> inviteFinal = new CompletableFuture<>();
        CompletableFuture<SipResponse> cancelFinal = new CompletableFuture<>();
        CompletableFuture<SipRequest> serverAck = new CompletableFuture<>();
        CompletableFuture<SipRequest> serverCancel = new CompletableFuture<>();
        AtomicReference<SipRequest> originalInvite = new AtomicReference<>();
        AtomicReference<CancelCoordinator> serverCoordinator = new AtomicReference<>();

        InviteClientListener inviteClientListener = (transaction, response, context) -> {
            if (response.statusCode() < 200) {
                provisionalReceived.complete(null);
            } else {
                inviteFinal.complete(response);
            }
        };
        InviteServerListener inviteServerListener = new InviteServerListener() {
            @Override
            public void onInvite(
                    InviteServerHandle transaction,
                    SipRequest invite,
                    TransportContext context
            ) {
                originalInvite.set(invite);
                transaction.sendResponse(SipResponses.createResponse(invite, 180, "Ringing"));
            }

            @Override
            public void onCancel(
                    InviteServerHandle transaction,
                    SipRequest cancel,
                    TransportContext context
            ) {
                serverCancel.complete(cancel);
                transaction.sendResponse(SipResponses.createResponse(
                        originalInvite.get(),
                        487,
                        "Request Terminated",
                        "udp-cancelled"
                ));
            }

            @Override
            public void onAck(
                    InviteServerHandle transaction,
                    SipRequest ack,
                    TransportContext context
            ) {
                serverAck.complete(ack);
            }
        };
        NonInviteClientListener cancelClientListener = (transaction, response, context) ->
                cancelFinal.complete(response);
        NonInviteServerListener cancelServerListener = (transaction, request, context) -> {
            try {
                serverCoordinator.get().handleInboundCancel(transaction, request, context);
            } catch (Exception exception) {
                throw new IllegalStateException(exception);
            }
        };
        InviteClientListener ignoredInviteClient = (transaction, response, context) -> {
        };
        InviteServerListener ignoredInviteServer = (transaction, request, context) -> {
        };
        NonInviteClientListener ignoredNonInviteClient = (transaction, response, context) -> {
        };
        NonInviteServerListener ignoredNonInviteServer = (transaction, request, context) -> {
        };

        try (InviteTransactionManager inviteClient = new InviteTransactionManager(
                clientTransport::send,
                SipTimerConfig.DEFAULT,
                InviteTransactionConfig.DEFAULT,
                inviteClientListener,
                ignoredInviteServer
        ); NonInviteTransactionManager nonInviteClient = new NonInviteTransactionManager(
                clientTransport::send,
                SipTimerConfig.DEFAULT,
                NonInviteTransactionConfig.DEFAULT,
                cancelClientListener,
                ignoredNonInviteServer
        ); InviteTransactionManager inviteServer = new InviteTransactionManager(
                serverTransport::send,
                SipTimerConfig.DEFAULT,
                InviteTransactionConfig.DEFAULT,
                ignoredInviteClient,
                inviteServerListener
        ); NonInviteTransactionManager nonInviteServer = new NonInviteTransactionManager(
                serverTransport::send,
                SipTimerConfig.DEFAULT,
                NonInviteTransactionConfig.DEFAULT,
                ignoredNonInviteClient,
                cancelServerListener
        )) {
            CancelCoordinator clientCoordinator = new CancelCoordinator(inviteClient, nonInviteClient);
            serverCoordinator.set(new CancelCoordinator(inviteServer, nonInviteServer));
            clientHandler.delegateTo(new SipTransactionDispatcher(inviteClient, nonInviteClient));
            serverHandler.delegateTo(new SipTransactionDispatcher(inviteServer, nonInviteServer));
            serverTransport.start();
            clientTransport.start();

            InviteClientHandle invite = inviteClient.sendInvite(
                    invite(clientTransport.localEndpoint()),
                    serverTransport.localEndpoint()
            );
            provisionalReceived.get(5, TimeUnit.SECONDS);
            clientCoordinator.sendCancel(invite);

            SipResponse cancelResponse = cancelFinal.get(5, TimeUnit.SECONDS);
            SipResponse inviteResponse = inviteFinal.get(5, TimeUnit.SECONDS);
            SipRequest cancel = serverCancel.get(5, TimeUnit.SECONDS);
            SipRequest ack = serverAck.get(5, TimeUnit.SECONDS);

            assertEquals(200, cancelResponse.statusCode());
            assertEquals(487, inviteResponse.statusCode());
            assertEquals(SipMethod.CANCEL, cancel.method());
            assertEquals(SipMethod.ACK, ack.method());
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
        return new SipRequest(
                SipMethod.INVITE,
                SipUri.parse("sip:bob@example.com"),
                SipHeaders.builder()
                        .add("Via", "SIP/2.0/UDP " + host + ":" + local.address().getPort()
                                + ";branch=z9hG4bK-real-cancel;rport")
                        .add("Max-Forwards", "70")
                        .add("From", "<sip:alice@example.com>;tag=udp-client")
                        .add("To", "<sip:bob@example.com>")
                        .add("Call-ID", "real-cancel@example.com")
                        .add("CSeq", "1 INVITE")
                        .build()
        );
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
