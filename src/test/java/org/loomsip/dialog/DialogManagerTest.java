package org.loomsip.dialog;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.loomsip.message.SipHeaders;
import org.loomsip.message.SipUri;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Timeout(10)
class DialogManagerTest {

    @Test
    void derivesDialogIdAccordingToLocalRole() throws Exception {
        SipHeaders headers = SipHeaders.builder()
                .add("From", "<sip:alice@example.com>;tag=from-tag")
                .add("To", "<sip:bob@example.com>;tag=to-tag")
                .add("Call-ID", "call-1@example.com")
                .build();

        assertEquals(new DialogId("call-1@example.com", "from-tag", "to-tag"),
                DialogId.from(headers, DialogRole.UAC));
        assertEquals(new DialogId("call-1@example.com", "to-tag", "from-tag"),
                DialogId.from(headers, DialogRole.UAS));
    }

    @Test
    void transitionsEarlyConfirmedAndTerminatedInOrder() throws Exception {
        List<String> notifications = java.util.Collections.synchronizedList(new ArrayList<>());
        DialogLifecycleListener listener = new DialogLifecycleListener() {
            @Override
            public void onStateChanged(DialogHandle dialog, DialogState previous, DialogState current) {
                notifications.add(previous + "->" + current);
            }

            @Override
            public void onTerminated(DialogHandle dialog, DialogTerminationReason reason) {
                notifications.add("terminated:" + reason);
            }
        };

        try (TestManager testManager = new TestManager(listener)) {
            DialogHandle handle = testManager.manager.create(snapshot(true));

            await(testManager.manager.transition(
                    handle.id(), DialogState.CONFIRMED, DialogTerminationReason.EXPLICIT
            ));
            await(testManager.manager.transition(
                    handle.id(), DialogState.TERMINATED, DialogTerminationReason.EXPLICIT
            ));
            await(handle.terminated());

            assertEquals(DialogState.TERMINATED, handle.snapshot().state());
            assertEquals(0, testManager.manager.activeDialogs());
            assertEquals(List.of(
                    "EARLY->CONFIRMED",
                    "CONFIRMED->TERMINATED",
                    "terminated:EXPLICIT"
            ), notifications);
        }
    }

    @Test
    void rejectsInvalidTransitionAndRequiresTargetBeforeConfirmation() throws Exception {
        try (TestManager testManager = new TestManager(new DialogLifecycleListener() {
        })) {
            DialogHandle handle = testManager.manager.create(snapshot(false));

            assertFailed(testManager.manager.transition(
                    handle.id(), DialogState.CONFIRMED, DialogTerminationReason.EXPLICIT
            ), IllegalStateException.class);
            assertEquals(DialogState.EARLY, handle.snapshot().state());

            await(testManager.manager.updateRemoteTarget(
                    handle.id(), SipUri.parse("sip:bob@new-target.example.com")
            ));
            await(testManager.manager.transition(
                    handle.id(), DialogState.CONFIRMED, DialogTerminationReason.EXPLICIT
            ));
            assertFailed(testManager.manager.transition(
                    handle.id(), DialogState.EARLY, DialogTerminationReason.EXPLICIT
            ), IllegalStateException.class);
            assertEquals(DialogState.CONFIRMED, handle.snapshot().state());
        }
    }

    @Test
    void allocatesUniqueOrderedLocalSequencesAcrossConcurrentCallers() throws Exception {
        try (TestManager testManager = new TestManager(new DialogLifecycleListener() {
        }); ExecutorService callers = Executors.newVirtualThreadPerTaskExecutor()) {
            DialogHandle handle = testManager.manager.create(snapshot(true));
            List<Future<Long>> allocations = new ArrayList<>();
            for (int index = 0; index < 100; index++) {
                allocations.add(callers.submit(() -> await(
                        testManager.manager.nextLocalSequence(handle.id())
                )));
            }

            Set<Long> sequences = new TreeSet<>();
            for (Future<Long> allocation : allocations) {
                sequences.add(allocation.get(2, TimeUnit.SECONDS));
            }

            assertEquals(100, sequences.size());
            assertEquals(11L, sequences.iterator().next());
            assertEquals(110L, sequences.stream().reduce((first, second) -> second).orElseThrow());
            assertEquals(110L, handle.snapshot().localCSeq());
        }
    }

    @Test
    void rejectsRemoteSequenceRegressionAndUpdatesRemoteTarget() throws Exception {
        try (TestManager testManager = new TestManager(new DialogLifecycleListener() {
        })) {
            DialogHandle handle = testManager.manager.create(snapshot(true));

            await(testManager.manager.acceptRemoteSequence(handle.id(), 21));
            assertFailed(testManager.manager.acceptRemoteSequence(handle.id(), 21),
                    IllegalArgumentException.class);
            assertFailed(testManager.manager.acceptRemoteSequence(handle.id(), 19),
                    IllegalArgumentException.class);
            await(testManager.manager.updateRemoteTarget(
                    handle.id(), SipUri.parse("sip:bob@refreshed.example.com")
            ));

            assertEquals(21, handle.snapshot().remoteCSeq());
            assertEquals(SipUri.parse("sip:bob@refreshed.example.com"),
                    handle.snapshot().remoteTarget().orElseThrow());
        }
    }

    @Test
    void blockingTuCallbackDoesNotBlockDialogMailbox() throws Exception {
        CountDownLatch callbackEntered = new CountDownLatch(1);
        CountDownLatch releaseCallback = new CountDownLatch(1);
        DialogLifecycleListener listener = new DialogLifecycleListener() {
            @Override
            public void onStateChanged(DialogHandle dialog, DialogState previous, DialogState current) {
                if (current == DialogState.CONFIRMED) {
                    callbackEntered.countDown();
                    try {
                        releaseCallback.await();
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        };

        try (TestManager testManager = new TestManager(listener)) {
            DialogHandle handle = testManager.manager.create(snapshot(true));
            try {
                await(testManager.manager.transition(
                        handle.id(), DialogState.CONFIRMED, DialogTerminationReason.EXPLICIT
                ));
                assertTrue(callbackEntered.await(2, TimeUnit.SECONDS));

                assertEquals(11L, await(testManager.manager.nextLocalSequence(handle.id())));
                assertEquals(DialogState.CONFIRMED, handle.snapshot().state());
            } finally {
                releaseCallback.countDown();
            }
        }
    }

    @Test
    void managerCloseTerminatesAndRemovesEveryDialog() throws Exception {
        TestManager testManager = new TestManager(new DialogLifecycleListener() {
        });
        try {
            DialogHandle first = testManager.manager.create(snapshot(true));
            DialogSnapshot secondSnapshot = snapshot(true);
            secondSnapshot = new DialogSnapshot(
                    new DialogId(secondSnapshot.id().callId(), secondSnapshot.id().localTag(), "fork-2"),
                    secondSnapshot.role(),
                    secondSnapshot.state(),
                    secondSnapshot.localUri(),
                    secondSnapshot.remoteUri(),
                    secondSnapshot.localCSeq(),
                    secondSnapshot.remoteCSeq(),
                    secondSnapshot.routeSet(),
                    secondSnapshot.remoteTarget(),
                    secondSnapshot.secure()
            );
            DialogHandle second = testManager.manager.create(secondSnapshot);

            testManager.manager.close();
            await(first.terminated());
            await(second.terminated());

            assertEquals(0, testManager.manager.activeDialogs());
            assertFalse(testManager.manager.find(first.id()).isPresent());
            assertFalse(testManager.manager.find(second.id()).isPresent());
        } finally {
            testManager.closeExecutors();
        }
    }

    private static DialogSnapshot snapshot(boolean withRemoteTarget) {
        return new DialogSnapshot(
                new DialogId("call-1@example.com", "local", "remote"),
                DialogRole.UAC,
                DialogState.EARLY,
                SipUri.parse("sip:alice@example.com"),
                SipUri.parse("sip:bob@example.com"),
                10,
                20,
                List.of(),
                withRemoteTarget
                        ? Optional.of(SipUri.parse("sip:bob@target.example.com"))
                        : Optional.empty(),
                false
        );
    }

    private static <T> T await(CompletionStage<T> stage) throws Exception {
        return stage.toCompletableFuture().get(2, TimeUnit.SECONDS);
    }

    private static void assertFailed(
            CompletionStage<?> stage,
            Class<? extends Throwable> expectedCause
    ) {
        ExecutionException exception = assertThrows(
                ExecutionException.class,
                () -> stage.toCompletableFuture().get(2, TimeUnit.SECONDS)
        );
        assertTrue(expectedCause.isInstance(exception.getCause()),
                () -> "unexpected failure: " + exception.getCause());
    }

    private static final class TestManager implements AutoCloseable {

        private final ExecutorService dialogExecutor = Executors.newVirtualThreadPerTaskExecutor();
        private final ExecutorService callbackExecutor = Executors.newVirtualThreadPerTaskExecutor();
        private final DialogManager manager;

        private TestManager(DialogLifecycleListener listener) {
            manager = new DialogManager(
                    new DialogConfig(10, 256, 64),
                    listener,
                    new InMemoryDialogRepository(10),
                    dialogExecutor,
                    callbackExecutor
            );
        }

        @Override
        public void close() {
            manager.close();
            closeExecutors();
        }

        private void closeExecutors() {
            callbackExecutor.close();
            dialogExecutor.close();
        }
    }
}
