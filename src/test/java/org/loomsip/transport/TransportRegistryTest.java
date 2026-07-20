package org.loomsip.transport;

import org.junit.jupiter.api.Test;
import org.loomsip.message.SipBody;
import org.loomsip.message.SipHeaders;
import org.loomsip.message.SipMethod;
import org.loomsip.message.SipRequest;
import org.loomsip.message.SipUri;
import org.loomsip.message.SipVersion;
import org.loomsip.transaction.ConnectionAwareMessageSender;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.EnumSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TransportRegistryTest {

    @Test
    void selectsEveryRegisteredProtocolAndOwnsLifecycle() throws Exception {
        RecordingTransport udp = new RecordingTransport(TransportProtocol.UDP);
        RecordingTransport tcp = new RecordingTransport(TransportProtocol.TCP);
        RecordingTransport tls = new RecordingTransport(TransportProtocol.TLS);
        TransportRegistry registry = new TransportRegistry();
        registry.register(TransportProtocol.UDP, udp);
        registry.register(TransportProtocol.TCP, tcp);
        registry.register(TransportProtocol.TLS, tls);
        TransportSelector selector = new TransportSelector(registry);

        assertThrows(ExecutionException.class, () -> selector.send(
                request(), endpoint(TransportProtocol.UDP, 20001)
        ).toCompletableFuture().get(1, TimeUnit.SECONDS));

        registry.start();
        selector.send(request(), endpoint(TransportProtocol.UDP, 20001)).toCompletableFuture().get();
        selector.send(request(), endpoint(TransportProtocol.TCP, 20002)).toCompletableFuture().get();
        selector.send(request(), endpoint(TransportProtocol.TLS, 20003)).toCompletableFuture().get();

        assertEquals(EnumSet.allOf(TransportProtocol.class), EnumSet.copyOf(
                registry.transports().keySet()
        ));
        assertEquals(1, udp.sendCount.get());
        assertEquals(1, tcp.sendCount.get());
        assertEquals(1, tls.sendCount.get());
        assertTrue(registry.isStarted());

        registry.close();
        assertEquals(1, udp.closeCount.get());
        assertEquals(1, tcp.closeCount.get());
        assertEquals(1, tls.closeCount.get());
        assertThrows(ExecutionException.class, () -> selector.send(
                request(), endpoint(TransportProtocol.TCP, 20002)
        ).toCompletableFuture().get(1, TimeUnit.SECONDS));
    }

    @Test
    void rejectsDuplicateProtocolsAndMissingTransport() throws Exception {
        TransportRegistry registry = new TransportRegistry();
        registry.register(TransportProtocol.UDP, new RecordingTransport(TransportProtocol.UDP));
        assertThrows(IllegalArgumentException.class, () -> registry.register(
                TransportProtocol.UDP,
                new RecordingTransport(TransportProtocol.UDP)
        ));
        registry.start();

        ExecutionException failure = assertThrows(ExecutionException.class, () -> registry.send(
                request(), endpoint(TransportProtocol.TCP, 20004)
        ).toCompletableFuture().get(1, TimeUnit.SECONDS));
        assertInstanceOf(IllegalArgumentException.class, failure.getCause());
        registry.close();
    }

    @Test
    void connectionAwareSenderReportsFailureWithoutReplacingStage() throws Exception {
        RecordingTransport tcp = new RecordingTransport(TransportProtocol.TCP);
        TransportRegistry registry = new TransportRegistry();
        registry.register(TransportProtocol.TCP, tcp);
        registry.start();

        AtomicReference<TransportFailureEvent> observed = new AtomicReference<>();
        ConnectionAwareMessageSender sender = new ConnectionAwareMessageSender(
                new TransportSelector(registry),
                observed::set
        );
        tcp.failure = new TransportException("write failed");

        ExecutionException failure = assertThrows(ExecutionException.class, () -> sender.send(
                request(), endpoint(TransportProtocol.TCP, 20005)
        ).toCompletableFuture().get(1, TimeUnit.SECONDS));

        assertEquals(tcp.failure, failure.getCause());
        assertEquals(endpoint(TransportProtocol.TCP, 20005), observed.get().target());
        assertEquals(TransportProtocol.TCP, observed.get().protocol());
        registry.close();
    }

    private static TransportEndpoint endpoint(TransportProtocol protocol, int port) {
        return new TransportEndpoint(protocol, new InetSocketAddress(
                InetAddress.getLoopbackAddress(),
                port
        ));
    }

    private static SipRequest request() {
        return new SipRequest(
                SipMethod.OPTIONS,
                SipUri.parse("sip:service@example.com"),
                SipVersion.SIP_2_0,
                SipHeaders.builder()
                        .add("Call-ID", "registry-test@example.com")
                        .add("CSeq", "1 OPTIONS")
                        .build(),
                SipBody.empty()
        );
    }

    private static final class RecordingTransport implements SipTransport {

        private final TransportProtocol protocol;
        private final AtomicInteger sendCount = new AtomicInteger();
        private final AtomicInteger closeCount = new AtomicInteger();
        private volatile TransportState state = TransportState.NEW;
        private volatile TransportException failure;

        private RecordingTransport(TransportProtocol protocol) {
            this.protocol = protocol;
        }

        @Override
        public void start() {
            state = TransportState.RUNNING;
        }

        @Override
        public java.util.concurrent.CompletionStage<SendResult> send(
                org.loomsip.message.SipMessage message,
                TransportEndpoint target
        ) {
            sendCount.incrementAndGet();
            if (failure != null) {
                return CompletableFuture.failedFuture(failure);
            }
            return CompletableFuture.completedFuture(new SendResult(
                    endpoint(protocol, 10000),
                    target,
                    1
            ));
        }

        @Override
        public TransportEndpoint localEndpoint() {
            return endpoint(protocol, 10000);
        }

        @Override
        public TransportState state() {
            return state;
        }

        @Override
        public void close() {
            closeCount.incrementAndGet();
            state = TransportState.CLOSED;
        }
    }
}
