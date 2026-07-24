package org.loomsip.stack;

import org.junit.jupiter.api.Test;
import org.loomsip.message.SipMessage;
import org.loomsip.message.SipHeaders;
import org.loomsip.message.SipMethod;
import org.loomsip.message.SipRequest;
import org.loomsip.message.SipUri;
import org.loomsip.transaction.timer.Cancellable;
import org.loomsip.transaction.timer.SipScheduler;
import org.loomsip.transport.SendResult;
import org.loomsip.transport.SipTransport;
import org.loomsip.transport.TransportEndpoint;
import org.loomsip.transport.TransportException;
import org.loomsip.transport.TransportProtocol;
import org.loomsip.transport.TransportRegistry;
import org.loomsip.transport.TransportState;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LoomSipStackTest {

    @Test
    void startsOnceAndClosesTransferredTransportRegistry() {
        RecordingTransport transport = new RecordingTransport(false);
        TransportRegistry registry = new TransportRegistry();
        registry.register(TransportProtocol.UDP, transport);
        LoomSipStack stack = LoomSipStack.builder().transportRegistry(registry).build();

        stack.start().toCompletableFuture().join();
        stack.start().toCompletableFuture().join();

        assertEquals(SipStackState.RUNNING, stack.state());
        assertEquals(1, transport.starts.get());
        stack.close();
        assertEquals(SipStackState.CLOSED, stack.state());
        assertEquals(1, transport.closes.get());
    }

    @Test
    void startupFailureTransitionsToFailedAndCleansOwnedResources() {
        RecordingTransport transport = new RecordingTransport(true);
        TransportRegistry registry = new TransportRegistry();
        registry.register(TransportProtocol.UDP, transport);
        LoomSipStack stack = LoomSipStack.builder().transportRegistry(registry).build();

        assertThrows(java.util.concurrent.CompletionException.class, () -> stack.start().toCompletableFuture().join());

        assertEquals(SipStackState.FAILED, stack.state());
        stack.close();
        assertEquals(SipStackState.CLOSED, stack.state());
        assertEquals(1, transport.closes.get());
    }

    @Test
    void preservesExternallyOwnedResourcesOnClose() {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        RecordingScheduler scheduler = new RecordingScheduler();
        LoomSipStack stack = LoomSipStack.builder()
                .resources(StackResources.external(executor, scheduler))
                .build();
        try {
            stack.start().toCompletableFuture().join();
            stack.close();

            assertFalse(executor.isShutdown());
            assertFalse(scheduler.closed.get());
        } finally {
            executor.shutdownNow();
            scheduler.close();
        }
    }

    @Test
    void rejectsStartAfterClose() {
        LoomSipStack stack = LoomSipStack.builder().build();
        stack.close();

        assertTrue(stack.closeAsync().toCompletableFuture().isDone());
        assertThrows(java.util.concurrent.CompletionException.class, () -> stack.start().toCompletableFuture().join());
    }

    @Test
    void builderCannotCreateMoreThanOneStack() {
        LoomSipStackBuilder builder = LoomSipStack.builder();
        LoomSipStack stack = builder.build();
        try {
            assertThrows(IllegalStateException.class, builder::build);
            assertThrows(IllegalStateException.class, () -> builder.config(SipStackConfig.DEFAULT));
        } finally {
            stack.close();
        }
    }

    @Test
    void createsConfiguredFactoriesOnlyWhenStackStarts() {
        RecordingTransport udp = new RecordingTransport(false);
        RecordingTransport tcp = new RecordingTransport(false);
        AtomicInteger udpFactoryCalls = new AtomicInteger();
        AtomicInteger tcpFactoryCalls = new AtomicInteger();
        LoomSipStack stack = LoomSipStack.builder()
                .transport(TransportProtocol.UDP, ignored -> {
                    udpFactoryCalls.incrementAndGet();
                    return udp;
                })
                .transport(TransportProtocol.TCP, ignored -> {
                    tcpFactoryCalls.incrementAndGet();
                    return tcp;
                })
                .build();
        try {
            assertEquals(0, udpFactoryCalls.get());
            assertEquals(0, tcpFactoryCalls.get());

            stack.start().toCompletableFuture().join();

            assertEquals(1, udpFactoryCalls.get());
            assertEquals(1, tcpFactoryCalls.get());
            assertEquals(1, udp.starts.get());
            assertEquals(1, tcp.starts.get());
        } finally {
            stack.close();
        }
        assertEquals(1, udp.closes.get());
        assertEquals(1, tcp.closes.get());
    }

    @Test
    void closesCreatedTransportWhenLaterFactoryFails() {
        RecordingTransport udp = new RecordingTransport(false);
        LoomSipStack stack = LoomSipStack.builder()
                .transport(TransportProtocol.UDP, ignored -> udp)
                .transport(TransportProtocol.TCP, ignored -> {
                    throw new IllegalStateException("test factory failure");
                })
                .build();

        assertThrows(java.util.concurrent.CompletionException.class, () -> stack.start().toCompletableFuture().join());

        assertEquals(SipStackState.FAILED, stack.state());
        assertEquals(0, udp.starts.get());
        assertEquals(1, udp.closes.get());
        stack.close();
        assertEquals(SipStackState.CLOSED, stack.state());
        assertEquals(1, udp.closes.get());
    }

    @Test
    void rejectsMixingFactoriesWithTransferredRegistry() {
        TransportRegistry registry = new TransportRegistry();
        assertThrows(IllegalStateException.class, () -> LoomSipStack.builder()
                .transportRegistry(registry)
                .transport(TransportProtocol.UDP, ignored -> new RecordingTransport(false)));
        assertThrows(IllegalStateException.class, () -> LoomSipStack.builder()
                .transport(TransportProtocol.UDP, ignored -> new RecordingTransport(false))
                .transportRegistry(registry));
    }

    @Test
    void clientAcceptsCommandsOnlyWhileStackIsRunning() throws Exception {
        RecordingTransport transport = new RecordingTransport(false);
        TransportRegistry registry = new TransportRegistry();
        registry.register(TransportProtocol.UDP, transport);
        LoomSipStack stack = LoomSipStack.builder().transportRegistry(registry).build();
        OutgoingRequest request = new OutgoingRequest(optionsRequest(), TransportEndpoint.udp(
                new InetSocketAddress(InetAddress.getLoopbackAddress(), 5070)
        ));

        assertThrows(IllegalStateException.class, () -> stack.client().request(request));
        stack.start().toCompletableFuture().join();
        assertEquals(optionsRequest(), stack.client().request(request).originalRequest());
        stack.close();
        assertThrows(IllegalStateException.class, () -> stack.client().request(request));
    }

    @Test
    void snapshotAndFailingListenerDoNotBlockLifecycle() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        RecordingScheduler scheduler = new RecordingScheduler();
        AtomicInteger states = new AtomicInteger();
        CountDownLatch observed = new CountDownLatch(1);
        LoomSipStack stack = LoomSipStack.builder()
                .resources(StackResources.external(executor, scheduler))
                .listener(new SipStackListener() {
                    @Override public void onStateChanged(StackStateSnapshot snapshot) {
                        states.incrementAndGet(); observed.countDown(); throw new IllegalStateException("listener failure");
                    }
                }).build();
        try {
            stack.start().toCompletableFuture().join();
            assertEquals(SipStackState.RUNNING, stack.snapshot().state());
            stack.close();
            assertTrue(observed.await(1, TimeUnit.SECONDS));
            assertEquals(SipStackState.CLOSED, stack.snapshot().state());
            assertTrue(states.get() >= 1);
        } finally {
            executor.shutdownNow(); scheduler.close();
        }
    }

    @Test
    void createsOptionalDialogRuntimeFromExplicitConfiguration() {
        DialogStackConfig dialog = new DialogStackConfig(
                org.loomsip.dialog.DialogRequestProfile.udp(
                        new org.loomsip.message.header.SentBy("127.0.0.1", 5060)),
                (uri, protocol) -> CompletableFuture.failedFuture(new IllegalStateException("not used")),
                org.loomsip.dialog.DialogConfig.DEFAULT,
                new org.loomsip.dialog.DialogLifecycleListener() { }
        );
        LoomSipStack stack = LoomSipStack.builder().dialog(dialog)
                .transport(TransportProtocol.UDP, ignored -> new RecordingTransport(false)).build();
        try {
            assertTrue(stack.dialogs().isPresent());
        } finally {
            stack.close();
        }
        assertThrows(IllegalArgumentException.class, () -> new DialogStackConfig(
                org.loomsip.dialog.DialogRequestProfile.udp(
                        new org.loomsip.message.header.SentBy("127.0.0.1", 0)),
                (uri, protocol) -> CompletableFuture.failedFuture(new IllegalStateException("not used")),
                org.loomsip.dialog.DialogConfig.DEFAULT,
                new org.loomsip.dialog.DialogLifecycleListener() { }
        ));
    }

    private static SipRequest optionsRequest() {
        return new SipRequest(SipMethod.OPTIONS, SipUri.parse("sip:service@example.com"), SipHeaders.builder()
                .add("Via", "SIP/2.0/UDP client.example.com;branch=z9hG4bK-stack-client")
                .add("From", "<sip:client@example.com>;tag=caller")
                .add("To", "<sip:service@example.com>")
                .add("Call-ID", "stack-client@example.com")
                .add("CSeq", "1 OPTIONS")
                .build());
    }

    private static final class RecordingTransport implements SipTransport {
        private final boolean failStart;
        private final AtomicInteger starts = new AtomicInteger();
        private final AtomicInteger closes = new AtomicInteger();
        private volatile TransportState state = TransportState.NEW;

        private RecordingTransport(boolean failStart) {
            this.failStart = failStart;
        }

        @Override
        public void start() throws TransportException {
            starts.incrementAndGet();
            if (failStart) {
                state = TransportState.FAILED;
                throw new TransportException("test startup failure");
            }
            state = TransportState.RUNNING;
        }

        @Override
        public java.util.concurrent.CompletionStage<SendResult> send(SipMessage message, TransportEndpoint target) {
            return CompletableFuture.completedFuture(new SendResult(localEndpoint(), target, 0));
        }

        @Override
        public TransportEndpoint localEndpoint() {
            try {
                return TransportEndpoint.udp(new InetSocketAddress(InetAddress.getLoopbackAddress(), 5060));
            } catch (Exception exception) {
                throw new AssertionError(exception);
            }
        }

        @Override public TransportState state() { return state; }

        @Override
        public void close() {
            closes.incrementAndGet();
            state = TransportState.CLOSED;
        }
    }

    private static final class RecordingScheduler implements SipScheduler {
        private final AtomicBoolean closed = new AtomicBoolean();

        @Override
        public Cancellable schedule(Duration delay, Runnable callback) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void close() {
            closed.set(true);
        }
    }
}
