package org.loomsip.transaction.timer;

import org.junit.jupiter.api.Test;
import org.loomsip.transaction.event.TimerExpired;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TransactionTimerManagerTest {

    @Test
    void replacesTimerAndEmitsOnlyCurrentGeneration() {
        VirtualSipScheduler scheduler = new VirtualSipScheduler();
        List<TimerExpired> events = new ArrayList<>();
        TransactionTimerManager timers = new TransactionTimerManager(
                scheduler,
                events::add,
                failure -> {
                    throw new AssertionError(failure);
                }
        );

        long first = timers.start(SipTimer.E, Duration.ofSeconds(1));
        long second = timers.start(SipTimer.E, Duration.ofSeconds(2));
        scheduler.advanceBy(Duration.ofSeconds(2));

        assertTrue(second > first);
        assertEquals(List.of(new TimerExpired(SipTimer.E, second)), events);
        assertFalse(timers.consumeIfCurrent(SipTimer.E, first));
        assertTrue(timers.consumeIfCurrent(SipTimer.E, second));
        assertTrue(timers.currentGeneration(SipTimer.E).isEmpty());
    }

    @Test
    void rejectsAlreadyQueuedStaleEventAfterReplacement() {
        VirtualSipScheduler scheduler = new VirtualSipScheduler();
        List<TimerExpired> events = new ArrayList<>();
        TransactionTimerManager timers = new TransactionTimerManager(
                scheduler,
                events::add,
                failure -> {
                    throw new AssertionError(failure);
                }
        );

        long stale = timers.start(SipTimer.A, Duration.ZERO);
        scheduler.advanceBy(Duration.ZERO);
        long current = timers.start(SipTimer.A, Duration.ZERO);
        scheduler.advanceBy(Duration.ZERO);

        assertEquals(2, events.size());
        assertFalse(timers.consumeIfCurrent(SipTimer.A, stale));
        assertTrue(timers.consumeIfCurrent(SipTimer.A, current));
    }

    @Test
    void validatesConfigAndClosesTimers() {
        assertEquals(Duration.ofSeconds(32), SipTimerConfig.DEFAULT.sixtyFourT1());
        assertThrows(IllegalArgumentException.class, () -> new SipTimerConfig(
                Duration.ofSeconds(2),
                Duration.ofSeconds(1),
                Duration.ofSeconds(5)
        ));

        VirtualSipScheduler scheduler = new VirtualSipScheduler();
        TransactionTimerManager timers = new TransactionTimerManager(
                scheduler,
                ignored -> {
                },
                ignored -> {
                }
        );
        timers.start(SipTimer.F, Duration.ofSeconds(1));
        timers.close();

        assertEquals(1, scheduler.pendingCount());
        scheduler.advanceBy(Duration.ofSeconds(1));
        assertThrows(IllegalStateException.class,
                () -> timers.start(SipTimer.F, Duration.ofSeconds(1)));
    }

    @Test
    void productionSchedulerRunsCallbackAndRejectsAfterClose() throws Exception {
        CountDownLatch callback = new CountDownLatch(1);
        DefaultSipScheduler scheduler = new DefaultSipScheduler();
        scheduler.schedule(Duration.ZERO, callback::countDown);

        assertTrue(callback.await(5, TimeUnit.SECONDS));
        scheduler.close();
        assertThrows(IllegalStateException.class,
                () -> scheduler.schedule(Duration.ZERO, () -> {
                }));
    }
}
