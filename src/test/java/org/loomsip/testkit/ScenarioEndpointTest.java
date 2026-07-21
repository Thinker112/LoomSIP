package org.loomsip.testkit;

import org.junit.jupiter.api.Test;
import org.loomsip.dialog.DialogLifecycleListener;
import org.loomsip.message.SipHeaders;
import org.loomsip.message.SipMethod;
import org.loomsip.message.SipRequest;
import org.loomsip.message.SipUri;
import org.loomsip.transaction.TransactionMessageSender;
import org.loomsip.transaction.invite.InviteClientListener;
import org.loomsip.transaction.invite.InviteServerListener;
import org.loomsip.transaction.noninvite.NonInviteClientListener;
import org.loomsip.transaction.noninvite.NonInviteServerListener;
import org.loomsip.transaction.timer.SipTimerConfig;
import org.loomsip.transport.InboundSipMessage;
import org.loomsip.transport.SendResult;
import org.loomsip.transport.SipMessageHandler;
import org.loomsip.transport.SipTransport;
import org.loomsip.transport.TransportContext;
import org.loomsip.transport.TransportEndpoint;
import org.loomsip.transport.TransportProtocol;
import org.loomsip.transport.TransportState;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ScenarioEndpointTest {

    @Test
    void bindsTransportCallbacksToAssembledTransactionDispatcher() throws Exception {
        TransportEndpoint local = TransportEndpoint.udp(new InetSocketAddress(InetAddress.getLoopbackAddress(), 25060));
        TransportEndpoint remote = TransportEndpoint.udp(new InetSocketAddress(InetAddress.getLoopbackAddress(), 25061));
        CountDownLatch delivered = new CountDownLatch(1);
        TransactionMessageSender sender = (message, target) -> CompletableFuture.completedFuture(
                new SendResult(local, target, 1)
        );
        ScenarioInboundHandler inbound = new ScenarioInboundHandler();
        try (ScenarioEndpoint endpoint = ScenarioEndpoint.create(
                new FixedTransport(local),
                sender,
                remote,
                SipTimerConfig.DEFAULT,
                new DialogLifecycleListener() {
                },
                (transaction, response, context) -> {
                },
                (transaction, request, context) -> {
                },
                (transaction, response, context) -> {
                },
                (transaction, request, context) -> delivered.countDown(),
                null
        )) {
            inbound.bind(endpoint.dispatcher());
            inbound.onMessage(new InboundSipMessage(
                    optionsRequest(),
                    new TransportContext(TransportProtocol.UDP, local.address(), remote.address())
            ));

            assertTrue(delivered.await(1, TimeUnit.SECONDS));
        }
    }

    @Test
    void transportPairOwnsBothTransportLifecycles() throws Exception {
        FixedTransport client = new FixedTransport(TransportEndpoint.udp(
                new InetSocketAddress(InetAddress.getLoopbackAddress(), 25062)
        ));
        FixedTransport server = new FixedTransport(TransportEndpoint.udp(
                new InetSocketAddress(InetAddress.getLoopbackAddress(), 25063)
        ));

        try (ScenarioTransportPair ignored = ScenarioTransportPair.start(client, server)) {
            assertTrue(client.started);
            assertTrue(server.started);
        }

        assertTrue(client.closed);
        assertTrue(server.closed);
    }

    @Test
    void closedEndpointIgnoresLateInboundRequest() throws Exception {
        TransportEndpoint local = TransportEndpoint.udp(new InetSocketAddress(InetAddress.getLoopbackAddress(), 25064));
        TransportEndpoint remote = TransportEndpoint.udp(new InetSocketAddress(InetAddress.getLoopbackAddress(), 25065));
        AtomicInteger callbacks = new AtomicInteger();
        TransactionMessageSender sender = (message, target) -> CompletableFuture.completedFuture(
                new SendResult(local, target, 1)
        );
        ScenarioInboundHandler inbound = new ScenarioInboundHandler();
        ScenarioEndpoint endpoint = ScenarioEndpoint.create(
                new FixedTransport(local),
                sender,
                remote,
                SipTimerConfig.DEFAULT,
                new DialogLifecycleListener() {
                },
                (transaction, response, context) -> {
                },
                (transaction, request, context) -> {
                },
                (transaction, response, context) -> {
                },
                (transaction, request, context) -> callbacks.incrementAndGet(),
                null
        );
        try {
            inbound.bind(endpoint.dispatcher());
            endpoint.close();

            inbound.onMessage(new InboundSipMessage(
                    optionsRequest(),
                    new TransportContext(TransportProtocol.UDP, local.address(), remote.address())
            ));

            assertTrue(callbacks.get() == 0);
            assertTrue(endpoint.nonInviteTransactions().activeServerTransactions() == 0);
        } finally {
            endpoint.close();
        }
    }

    private static SipRequest optionsRequest() {
        return new SipRequest(
                SipMethod.of("OPTIONS"),
                SipUri.parse("sip:bob@example.com"),
                SipHeaders.builder()
                        .add("Via", "SIP/2.0/UDP client.example.com;branch=z9hG4bK-6g-test")
                        .add("Max-Forwards", "70")
                        .add("From", "<sip:alice@example.com>;tag=client-tag")
                        .add("To", "<sip:bob@example.com>")
                        .add("Call-ID", "6g-scenario@example.com")
                        .add("CSeq", "1 OPTIONS")
                        .build()
        );
    }

    private static final class FixedTransport implements SipTransport {

        private final TransportEndpoint endpoint;
        private boolean started;
        private boolean closed;

        private FixedTransport(TransportEndpoint endpoint) {
            this.endpoint = endpoint;
        }

        @Override
        public void start() {
            started = true;
        }

        @Override
        public java.util.concurrent.CompletionStage<SendResult> send(
                org.loomsip.message.SipMessage message,
                TransportEndpoint target
        ) {
            return CompletableFuture.completedFuture(new SendResult(endpoint, target, 1));
        }

        @Override
        public TransportEndpoint localEndpoint() {
            return endpoint;
        }

        @Override
        public TransportState state() {
            return TransportState.RUNNING;
        }

        @Override
        public void close() {
            closed = true;
        }
    }
}
