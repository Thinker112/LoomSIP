package org.loomsip.dialog;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.loomsip.message.SipHeaders;
import org.loomsip.message.SipMethod;
import org.loomsip.message.SipRequest;
import org.loomsip.message.SipResponse;
import org.loomsip.message.SipUri;
import org.loomsip.transaction.TransactionMessageSender;
import org.loomsip.transaction.TransportReliability;
import org.loomsip.transaction.timer.SipTimerConfig;
import org.loomsip.transaction.timer.VirtualSipScheduler;
import org.loomsip.transport.SendResult;
import org.loomsip.transport.TransportContext;
import org.loomsip.transport.TransportEndpoint;
import org.loomsip.transport.TransportProtocol;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Timeout(10)
class DialogReliabilityTest {

    private static final SipTimerConfig TIMERS = SipTimerConfig.DEFAULT;

    @Test
    void unreliableSuccessRetransmitsUntilMatchingAckAndAbsorbsDuplicateAck() throws Exception {
        try (TestRig rig = new TestRig()) {
            DialogHandle dialog = rig.createUasDialog();
            await(rig.manager.registerUasSuccess(
                    dialog.id(),
                    response(1),
                    rig.remote,
                    TransportReliability.UNRELIABLE
            ));

            rig.scheduler.advanceBy(TIMERS.t1());
            rig.scheduler.advanceBy(TIMERS.t1().multipliedBy(2));
            assertEquals(2, rig.sender.responses.size());

            SipRequest ack = ack(1);
            assertTrue(await(rig.manager.receiveAck(dialog.id(), ack, rig.context)));
            assertTrue(await(rig.manager.receiveAck(dialog.id(), ack, rig.context)));
            assertEquals(1, rig.listener.acks.size());

            rig.scheduler.advanceBy(TIMERS.sixtyFourT1());
            assertEquals(2, rig.sender.responses.size());
            assertTrue(rig.listener.timeouts.isEmpty());
        }
    }

    @Test
    void wrongCseqDoesNotCancelRetransmission() throws Exception {
        try (TestRig rig = new TestRig()) {
            DialogHandle dialog = rig.createUasDialog();
            await(rig.manager.registerUasSuccess(
                    dialog.id(), response(1), rig.remote, TransportReliability.UNRELIABLE
            ));

            assertFalse(await(rig.manager.receiveAck(dialog.id(), ack(2), rig.context)));
            rig.scheduler.advanceBy(TIMERS.t1());

            assertEquals(1, rig.sender.responses.size());
            assertTrue(rig.listener.acks.isEmpty());
        }
    }

    @Test
    void ackTimeoutStopsRetransmissionButKeepsConfirmedDialog() throws Exception {
        try (TestRig rig = new TestRig()) {
            DialogHandle dialog = rig.createUasDialog();
            await(rig.manager.registerUasSuccess(
                    dialog.id(), response(1), rig.remote, TransportReliability.UNRELIABLE
            ));

            rig.scheduler.advanceBy(TIMERS.sixtyFourT1());
            int attemptsAtTimeout = rig.sender.responses.size();

            assertTrue(attemptsAtTimeout > 1);
            assertEquals(List.of(1L), rig.listener.timeouts);
            assertEquals(DialogState.CONFIRMED, dialog.snapshot().state());

            rig.scheduler.advanceBy(TIMERS.t2().multipliedBy(2));
            assertEquals(attemptsAtTimeout, rig.sender.responses.size());
        }
    }

    @Test
    void reliableTransportDoesNotRetransmitButStillReportsAckTimeout() throws Exception {
        try (TestRig rig = new TestRig()) {
            DialogHandle dialog = rig.createUasDialog();
            await(rig.manager.registerUasSuccess(
                    dialog.id(), response(1), rig.remote, TransportReliability.RELIABLE
            ));

            rig.scheduler.advanceBy(TIMERS.sixtyFourT1());

            assertTrue(rig.sender.responses.isEmpty());
            assertEquals(List.of(1L), rig.listener.timeouts);
        }
    }

    @Test
    void managerCloseCancelsPendingDialogTimers() throws Exception {
        TestRig rig = new TestRig();
        try {
            DialogHandle dialog = rig.createUasDialog();
            await(rig.manager.registerUasSuccess(
                    dialog.id(), response(1), rig.remote, TransportReliability.UNRELIABLE
            ));

            rig.manager.close();
            rig.scheduler.advanceBy(TIMERS.sixtyFourT1());

            assertTrue(rig.sender.responses.isEmpty());
            assertTrue(rig.listener.timeouts.isEmpty());
        } finally {
            rig.close();
        }
    }

    @Test
    void blockingAckCallbackDoesNotBlockDialogMailbox() throws Exception {
        VirtualSipScheduler scheduler = new VirtualSipScheduler();
        RecordingSender sender = new RecordingSender();
        CountDownLatch callbackEntered = new CountDownLatch(1);
        CountDownLatch releaseCallback = new CountDownLatch(1);
        DialogLifecycleListener listener = new DialogLifecycleListener() {
            @Override
            public void onAckReceived(
                    DialogHandle dialog,
                    SipRequest ack,
                    TransportContext context
            ) {
                callbackEntered.countDown();
                try {
                    releaseCallback.await();
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
            }
        };
        try (ExecutorService dialogExecutor = Executors.newVirtualThreadPerTaskExecutor();
                ExecutorService callbackExecutor = Executors.newVirtualThreadPerTaskExecutor()) {
            TransportEndpoint remote = endpoint(20161);
            DialogRuntime runtime = new DialogRuntime(
                    sender,
                    (uri, protocol) -> CompletableFuture.completedFuture(remote),
                    scheduler,
                    TIMERS,
                    () -> "z9hG4bK-blocking-callback",
                    Runnable::run
            );
            DialogManager manager = new DialogManager(
                    new DialogConfig(4, 32, 16),
                    listener,
                    new InMemoryDialogRepository(4),
                    dialogExecutor,
                    callbackExecutor,
                    runtime
            );
            try {
                DialogHandle dialog = manager.create(new DialogSnapshot(
                        new DialogId("reliability@example.com", "local-tag", "remote-tag"),
                        DialogRole.UAS,
                        DialogState.CONFIRMED,
                        SipUri.parse("sip:bob@example.com"),
                        SipUri.parse("sip:alice@example.com"),
                        0,
                        1,
                        List.of(),
                        Optional.of(SipUri.parse("sip:alice@client.example.com")),
                        false
                ));
                await(manager.registerUasSuccess(
                        dialog.id(), response(1), remote, TransportReliability.UNRELIABLE
                ));

                assertTrue(await(manager.receiveAck(
                        dialog.id(),
                        ack(1),
                        new TransportContext(
                                TransportProtocol.UDP,
                                endpoint(20160).address(),
                                remote.address()
                        )
                )));
                assertTrue(callbackEntered.await(2, TimeUnit.SECONDS));

                assertEquals(1L, await(manager.nextLocalSequence(dialog.id())));
            } finally {
                releaseCallback.countDown();
                manager.close();
                scheduler.close();
            }
        }
    }

    @Test
    void synchronousSenderReentryDoesNotDeadlockDialogMailbox() throws Exception {
        VirtualSipScheduler scheduler = new VirtualSipScheduler();
        TransportEndpoint local = endpoint(20260);
        TransportEndpoint remote = endpoint(20261);
        TransportContext context = new TransportContext(
                TransportProtocol.UDP,
                local.address(),
                remote.address()
        );
        AtomicReference<DialogManager> managerReference = new AtomicReference<>();
        CompletableFuture<Boolean> routedAck = new CompletableFuture<>();
        TransactionMessageSender sender = (message, target) -> {
            try {
                DialogManager manager = managerReference.get();
                DialogId id = DialogId.from(message.headers(), DialogRole.UAS);
                boolean matched = manager.receiveAck(id, ack(1), context)
                        .toCompletableFuture()
                        .join();
                routedAck.complete(matched);
                return CompletableFuture.completedFuture(new SendResult(target, target, 1));
            } catch (Throwable cause) {
                routedAck.completeExceptionally(cause);
                return CompletableFuture.failedFuture(cause);
            }
        };
        try (ExecutorService transportExecutor = Executors.newSingleThreadExecutor()) {
            DialogRuntime runtime = new DialogRuntime(
                    sender,
                    (uri, protocol) -> CompletableFuture.completedFuture(remote),
                    scheduler,
                    TIMERS,
                    () -> "z9hG4bK-synchronous-reentry",
                    transportExecutor
            );
            DialogManager manager = new DialogManager(
                    new DialogConfig(4, 32, 16),
                    new RecordingListener(),
                    new InMemoryDialogRepository(4),
                    Runnable::run,
                    Runnable::run,
                    runtime
            );
            managerReference.set(manager);
            try {
                DialogHandle dialog = manager.create(new DialogSnapshot(
                        new DialogId("reliability@example.com", "local-tag", "remote-tag"),
                        DialogRole.UAS,
                        DialogState.CONFIRMED,
                        SipUri.parse("sip:bob@example.com"),
                        SipUri.parse("sip:alice@example.com"),
                        0,
                        1,
                        List.of(),
                        Optional.of(SipUri.parse("sip:alice@client.example.com")),
                        false
                ));
                await(manager.registerUasSuccess(
                        dialog.id(), response(1), remote, TransportReliability.UNRELIABLE
                ));

                scheduler.advanceBy(TIMERS.t1());

                assertTrue(routedAck.get(2, TimeUnit.SECONDS));
                scheduler.advanceBy(TIMERS.sixtyFourT1());
            } finally {
                manager.close();
                scheduler.close();
            }
        }
    }

    private static SipResponse response(long cseq) {
        return new SipResponse(
                200,
                "OK",
                SipHeaders.builder()
                        .add("Via", "SIP/2.0/UDP client.example.com;branch=z9hG4bK-invite")
                        .add("From", "<sip:alice@example.com>;tag=remote-tag")
                        .add("To", "<sip:bob@example.com>;tag=local-tag")
                        .add("Call-ID", "reliability@example.com")
                        .add("CSeq", cseq + " INVITE")
                        .add("Contact", "<sip:bob@server.example.com>")
                        .build()
        );
    }

    private static SipRequest ack(long cseq) {
        return new SipRequest(
                SipMethod.ACK,
                SipUri.parse("sip:bob@server.example.com"),
                SipHeaders.builder()
                        .add("Via", "SIP/2.0/UDP client.example.com;branch=z9hG4bK-ack")
                        .add("From", "<sip:alice@example.com>;tag=remote-tag")
                        .add("To", "<sip:bob@example.com>;tag=local-tag")
                        .add("Call-ID", "reliability@example.com")
                        .add("CSeq", cseq + " ACK")
                        .build()
        );
    }

    private static <T> T await(CompletionStage<T> stage) {
        return stage.toCompletableFuture().join();
    }

    private static final class TestRig implements AutoCloseable {

        private final VirtualSipScheduler scheduler = new VirtualSipScheduler();
        private final RecordingSender sender = new RecordingSender();
        private final RecordingListener listener = new RecordingListener();
        private final TransportEndpoint local;
        private final TransportEndpoint remote;
        private final TransportContext context;
        private final DialogManager manager;

        private TestRig() throws Exception {
            local = endpoint(20060);
            remote = endpoint(20061);
            context = new TransportContext(TransportProtocol.UDP, local.address(), remote.address());
            DialogRuntime runtime = new DialogRuntime(
                    sender,
                    (uri, protocol) -> CompletableFuture.completedFuture(remote),
                    scheduler,
                    TIMERS,
                    () -> "z9hG4bK-reliability",
                    Runnable::run
            );
            manager = new DialogManager(
                    new DialogConfig(8, 64, 32),
                    listener,
                    new InMemoryDialogRepository(8),
                    Runnable::run,
                    Runnable::run,
                    runtime
            );
        }

        private DialogHandle createUasDialog() {
            return manager.create(new DialogSnapshot(
                    new DialogId("reliability@example.com", "local-tag", "remote-tag"),
                    DialogRole.UAS,
                    DialogState.CONFIRMED,
                    SipUri.parse("sip:bob@example.com"),
                    SipUri.parse("sip:alice@example.com"),
                    0,
                    1,
                    List.of(),
                    Optional.of(SipUri.parse("sip:alice@client.example.com")),
                    false
            ));
        }

        @Override
        public void close() {
            manager.close();
            scheduler.close();
        }
    }

    private static final class RecordingSender implements TransactionMessageSender {

        private final List<SipResponse> responses = new ArrayList<>();

        @Override
        public CompletionStage<SendResult> send(
                org.loomsip.message.SipMessage message,
                TransportEndpoint target
        ) {
            responses.add((SipResponse) message);
            return CompletableFuture.completedFuture(new SendResult(target, target, 1));
        }
    }

    private static final class RecordingListener implements DialogLifecycleListener {

        private final List<SipRequest> acks = new ArrayList<>();
        private final List<Long> timeouts = new ArrayList<>();

        @Override
        public void onAckReceived(DialogHandle dialog, SipRequest ack, TransportContext context) {
            acks.add(ack);
        }

        @Override
        public void onAckTimeout(DialogHandle dialog, long inviteCSeq) {
            timeouts.add(inviteCSeq);
        }
    }

    private static TransportEndpoint endpoint(int port) throws Exception {
        return TransportEndpoint.udp(new InetSocketAddress(InetAddress.getLoopbackAddress(), port));
    }
}
