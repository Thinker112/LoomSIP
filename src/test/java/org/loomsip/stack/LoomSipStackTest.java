package org.loomsip.stack;

import org.junit.jupiter.api.Test;
import org.loomsip.message.SipMessage;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
