package org.loomsip.concurrent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.loomsip.transaction.tu.TuCallbackDispatcher;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Timeout(15)
class SerialMailboxTest {

    @Test
    void serializesConcurrentSubmittersWithoutLosingEvents() throws Exception {
        AtomicInteger activeHandlers = new AtomicInteger();
        AtomicInteger maximumActive = new AtomicInteger();
        List<Integer> handled = Collections.synchronizedList(new ArrayList<>());
        int eventCount = 2_000;
        CountDownLatch complete = new CountDownLatch(eventCount);

        try (ExecutorService mailboxExecutor = Executors.newVirtualThreadPerTaskExecutor();
             ExecutorService submitters = Executors.newVirtualThreadPerTaskExecutor()) {
            SerialMailbox<Integer> mailbox = new SerialMailbox<>(
                    mailboxExecutor,
                    event -> {
                        int active = activeHandlers.incrementAndGet();
                        maximumActive.accumulateAndGet(active, Math::max);
                        handled.add(event);
                        activeHandlers.decrementAndGet();
                        complete.countDown();
                    },
                    failure -> {
                        throw new AssertionError(failure);
                    },
                    eventCount
            );

            for (int event = 0; event < eventCount; event++) {
                int value = event;
                submitters.submit(() -> mailbox.submit(value));
            }

            assertTrue(complete.await(10, TimeUnit.SECONDS));
            mailbox.close();
            mailbox.closed().toCompletableFuture().get(5, TimeUnit.SECONDS);
        }

        assertEquals(eventCount, handled.size());
        assertEquals(eventCount, handled.stream().distinct().count());
        assertEquals(1, maximumActive.get());
    }

    @Test
    void reportsHandlerFailureAndContinuesDraining() throws Exception {
        List<Integer> handled = Collections.synchronizedList(new ArrayList<>());
        CompletableFuture<Throwable> failure = new CompletableFuture<>();

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            SerialMailbox<Integer> mailbox = new SerialMailbox<>(
                    executor,
                    event -> {
                        if (event == 2) {
                            throw new IllegalArgumentException("bad event");
                        }
                        handled.add(event);
                    },
                    failure::complete,
                    8
            );
            mailbox.submit(1);
            mailbox.submit(2);
            mailbox.submit(3);
            mailbox.close();
            mailbox.closed().toCompletableFuture().get(5, TimeUnit.SECONDS);
        }

        assertInstanceOf(IllegalArgumentException.class, failure.get(5, TimeUnit.SECONDS));
        assertEquals(List.of(1, 3), handled);
    }

    @Test
    void enforcesCapacityAndDrainsAcceptedEventsDuringClose() throws Exception {
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        List<Integer> handled = Collections.synchronizedList(new ArrayList<>());

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            SerialMailbox<Integer> mailbox = new SerialMailbox<>(
                    executor,
                    event -> {
                        if (event == 1) {
                            firstStarted.countDown();
                            await(releaseFirst);
                        }
                        handled.add(event);
                    },
                    failure -> {
                        throw new AssertionError(failure);
                    },
                    1
            );
            mailbox.submit(1);
            assertTrue(firstStarted.await(5, TimeUnit.SECONDS));
            mailbox.submit(2);
            assertThrows(MailboxFullException.class, () -> mailbox.submit(3));
            mailbox.close();
            assertThrows(MailboxClosedException.class, () -> mailbox.submit(4));
            releaseFirst.countDown();
            mailbox.closed().toCompletableFuture().get(5, TimeUnit.SECONDS);

            assertEquals(MailboxState.CLOSED, mailbox.state());
            assertEquals(List.of(1, 2), handled);
        }
    }

    @Test
    void blockingTuCallbackDoesNotBlockTransactionMailbox() throws Exception {
        CountDownLatch callbackStarted = new CountDownLatch(1);
        CountDownLatch releaseCallback = new CountDownLatch(1);
        CompletableFuture<Integer> secondTransactionEvent = new CompletableFuture<>();

        try (ExecutorService transactionExecutor = Executors.newVirtualThreadPerTaskExecutor();
             ExecutorService callbackExecutor = Executors.newVirtualThreadPerTaskExecutor()) {
            TuCallbackDispatcher<String> callbacks = new TuCallbackDispatcher<>(
                    callbackExecutor,
                    ignored -> {
                        callbackStarted.countDown();
                        await(releaseCallback);
                    },
                    failure -> {
                        throw new AssertionError(failure);
                    },
                    8
            );
            SerialMailbox<Integer> transactionMailbox = new SerialMailbox<>(
                    transactionExecutor,
                    event -> {
                        if (event == 1) {
                            callbacks.dispatch("request");
                        } else {
                            secondTransactionEvent.complete(event);
                        }
                    },
                    failure -> {
                        throw new AssertionError(failure);
                    },
                    8
            );

            transactionMailbox.submit(1);
            assertTrue(callbackStarted.await(5, TimeUnit.SECONDS));
            transactionMailbox.submit(2);
            assertEquals(2, secondTransactionEvent.get(5, TimeUnit.SECONDS));

            releaseCallback.countDown();
            transactionMailbox.close();
            callbacks.close();
            transactionMailbox.closed().toCompletableFuture().get(5, TimeUnit.SECONDS);
            callbacks.closed().toCompletableFuture().get(5, TimeUnit.SECONDS);
        }
    }

    private static void await(CountDownLatch latch) {
        boolean interrupted = false;
        while (true) {
            try {
                latch.await();
                break;
            } catch (InterruptedException exception) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
