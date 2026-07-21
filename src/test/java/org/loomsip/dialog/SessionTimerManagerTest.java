package org.loomsip.dialog;

import org.junit.jupiter.api.Test;
import org.loomsip.message.header.SessionRefresher;
import org.loomsip.transaction.timer.VirtualSipScheduler;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionStage;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SessionTimerManagerTest {

    @Test
    void refreshesAtHalfIntervalAndIgnoresReplacedGeneration() {
        VirtualSipScheduler scheduler = new VirtualSipScheduler();
        List<SessionTimerSignal> actions = new ArrayList<>();
        SessionTimerManager manager = new SessionTimerManager(scheduler, Runnable::run, actions::add, 8);
        SessionTimerNegotiator.NegotiatedSessionTimer timer =
                new SessionTimerNegotiator.NegotiatedSessionTimer(120, SessionRefresher.UAC, SessionRefreshMethod.UPDATE);

        await(manager.configure(timer, true));
        await(manager.configure(new SessionTimerNegotiator.NegotiatedSessionTimer(
                180, SessionRefresher.UAC, SessionRefreshMethod.UPDATE), true));
        scheduler.advanceBy(Duration.ofSeconds(60));
        assertEquals(List.of(), actions);
        scheduler.advanceBy(Duration.ofSeconds(30));
        assertEquals(SessionTimerAction.REFRESH, actions.getFirst().action());
        assertEquals(2, actions.getFirst().state().generation());
    }

    @Test
    void expiresWhenRemoteRefresherMissesFullInterval() {
        VirtualSipScheduler scheduler = new VirtualSipScheduler();
        List<SessionTimerSignal> actions = new ArrayList<>();
        SessionTimerManager manager = new SessionTimerManager(scheduler, Runnable::run, actions::add, 8);

        await(manager.configure(new SessionTimerNegotiator.NegotiatedSessionTimer(
                120, SessionRefresher.UAS, SessionRefreshMethod.UPDATE), false));
        scheduler.advanceBy(Duration.ofSeconds(119));
        assertEquals(List.of(), actions);
        scheduler.advanceBy(Duration.ofSeconds(1));
        assertEquals(SessionTimerAction.EXPIRE, actions.getFirst().action());
    }

    @Test
    void closingTimerSuppressesLateScheduledSignal() {
        VirtualSipScheduler scheduler = new VirtualSipScheduler();
        List<SessionTimerSignal> actions = new ArrayList<>();
        SessionTimerManager manager = new SessionTimerManager(scheduler, Runnable::run, actions::add, 8);

        await(manager.configure(new SessionTimerNegotiator.NegotiatedSessionTimer(
                120, SessionRefresher.UAC, SessionRefreshMethod.UPDATE
        ), true));
        manager.close();
        scheduler.advanceBy(Duration.ofSeconds(120));

        assertEquals(List.of(), actions);
    }

    private static <T> T await(CompletionStage<T> stage) {
        return stage.toCompletableFuture().join();
    }
}
