package org.loomsip.dialog;

import org.loomsip.concurrent.SerialMailbox;
import org.loomsip.transaction.timer.Cancellable;
import org.loomsip.transaction.timer.SipScheduler;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * Generation-safe RFC 4028 timer controller for one Dialog.
 *
 * <pre>{@code
 * negotiated Session-Expires
 *          |
 *          v
 * Session Timer Mailbox -- schedule --> SipScheduler
 *          ^                                  |
 *          +---------- current generation ----+
 *          |
 *    REFRESH or EXPIRE action
 * }</pre>
 *
 * <p>Reconfiguration cancels the previous deadline and increments generation.
 * A stale scheduler callback cannot emit an action for a newer negotiation.</p>
 */
public final class SessionTimerManager implements AutoCloseable {

    private final SipScheduler scheduler;
    private final Consumer<? super SessionTimerSignal> actionSink;
    private final SerialMailbox<Event> mailbox;
    private long generation;
    private Cancellable timer;
    private volatile DialogSessionState state;
    private boolean closed;

    /**
     * Creates a timer controller.
     *
     * @param scheduler stack-owned virtual or real scheduler
     * @param executor executor used for serialized timer state
     * @param actionSink receives generation-bearing refresh or expiry signals
     * @param mailboxCapacity queued event limit
     */
    public SessionTimerManager(
            SipScheduler scheduler,
            Executor executor,
            Consumer<? super SessionTimerSignal> actionSink,
            int mailboxCapacity
    ) {
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.actionSink = Objects.requireNonNull(actionSink, "actionSink");
        mailbox = new SerialMailbox<>(
                Objects.requireNonNull(executor, "executor"),
                this::handle,
                failure -> {
                    throw new IllegalStateException("Session Timer mailbox failed", failure);
                },
                mailboxCapacity
        );
    }

    /**
     * Replaces the current session deadline from negotiated parameters.
     *
     * @param negotiated negotiated interval, refresher, and method
     * @param localRefresher whether this Dialog endpoint is the refresher
     * @return newly installed immutable state
     */
    public CompletionStage<DialogSessionState> configure(
            SessionTimerNegotiator.NegotiatedSessionTimer negotiated,
            boolean localRefresher
    ) {
        CompletableFuture<DialogSessionState> result = new CompletableFuture<>();
        submit(new Configure(Objects.requireNonNull(negotiated, "negotiated"), localRefresher, result), result);
        return result.minimalCompletionStage();
    }

    /** Returns the latest state, or {@code null} before initial negotiation. */
    public DialogSessionState state() {
        return state;
    }

    /** Cancels the current deadline and prevents further actions. */
    @Override
    public void close() {
        try {
            mailbox.submit(new Close());
        } catch (RuntimeException ignored) {
            // Already closing.
        }
    }

    private void handle(Event event) {
        if (event instanceof Configure configure) {
            if (closed) {
                configure.result().completeExceptionally(new IllegalStateException("Session Timer is closed"));
                return;
            }
            cancelTimer();
            long next = ++generation;
            state = new DialogSessionState(
                    configure.negotiated().intervalSeconds(),
                    configure.localRefresher(),
                    configure.negotiated().refreshMethod(),
                    next
            );
            Duration delay = configure.localRefresher()
                    ? Duration.ofSeconds(Math.max(1, state.intervalSeconds() / 2L))
                    : Duration.ofSeconds(state.intervalSeconds());
            timer = scheduler.schedule(delay, () -> submitTimer(next));
            configure.result().complete(state);
        } else if (event instanceof TimerElapsed elapsed) {
            if (!closed && state != null && state.generation() == elapsed.generation()) {
                actionSink.accept(new SessionTimerSignal(
                        state.localRefresher() ? SessionTimerAction.REFRESH : SessionTimerAction.EXPIRE,
                        state
                ));
            }
        } else if (event instanceof Close) {
            closed = true;
            cancelTimer();
            mailbox.close();
        }
    }

    private void submitTimer(long timerGeneration) {
        try {
            mailbox.submit(new TimerElapsed(timerGeneration));
        } catch (RuntimeException ignored) {
            // Closed before callback delivery.
        }
    }

    private void cancelTimer() {
        if (timer != null) {
            timer.cancel();
            timer = null;
        }
    }

    private void submit(Event event, CompletableFuture<?> result) {
        try {
            mailbox.submit(event);
        } catch (Throwable cause) {
            result.completeExceptionally(cause);
        }
    }

    private sealed interface Event permits Configure, TimerElapsed, Close {
    }

    private record Configure(
            SessionTimerNegotiator.NegotiatedSessionTimer negotiated,
            boolean localRefresher,
            CompletableFuture<DialogSessionState> result
    ) implements Event {
    }

    private record TimerElapsed(long generation) implements Event {
    }

    private record Close() implements Event {
    }
}
