package org.loomsip.transaction.timer;

import org.loomsip.transaction.event.TimerExpired;

import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.function.Consumer;

/**
 * Allocates timer generations and converts scheduler callbacks into events.
 *
 * <p>Cancellation is best-effort. A callback already queued in a mailbox is
 * rejected later by {@link #consumeIfCurrent(SipTimer, long)}.</p>
 *
 * <pre>{@code
 * Transaction State Machine
 *          |
 *          | start/cancel(timer)
 *          v
 * +-------------------------+
 * | TransactionTimerManager |
 * | - allocate generation   |
 * | - replace registration  |
 * +------------+------------+
 *              |
 *              v
 *         SipScheduler
 *              |
 *              v
 * TimerExpired(timer, generation)
 *              |
 *              v
 *     Transaction Mailbox
 * }</pre>
 */
public final class TransactionTimerManager implements AutoCloseable {

    private static final System.Logger LOGGER = System.getLogger(TransactionTimerManager.class.getName());

    private final SipScheduler scheduler;
    private final Consumer<? super TimerExpired> eventSink;
    private final Consumer<? super Throwable> errorHandler;
    private final Map<SipTimer, Registration> registrations = new EnumMap<>(SipTimer.class);

    private long nextGeneration;
    private boolean closed;

    /**
     * Creates a timer manager for one transaction.
     *
     * @param scheduler shared scheduler
     * @param eventSink normally submits TimerExpired to the transaction mailbox
     * @param errorHandler receives event-sink failures
     */
    public TransactionTimerManager(
            SipScheduler scheduler,
            Consumer<? super TimerExpired> eventSink,
            Consumer<? super Throwable> errorHandler
    ) {
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.eventSink = Objects.requireNonNull(eventSink, "eventSink");
        this.errorHandler = Objects.requireNonNull(errorHandler, "errorHandler");
    }

    /**
     * Starts or replaces a named timer.
     *
     * @param timer timer name
     * @param delay non-negative relative delay
     * @return newly allocated generation
     * @throws IllegalStateException if this manager is closed
     */
    public synchronized long start(SipTimer timer, Duration delay) {
        Objects.requireNonNull(timer, "timer");
        Objects.requireNonNull(delay, "delay");
        if (closed) {
            throw new IllegalStateException("transaction timer manager is closed");
        }
        Registration previous = registrations.remove(timer);
        if (previous != null) {
            previous.cancel();
        }

        long generation = ++nextGeneration;
        Registration registration = new Registration(generation);
        registrations.put(timer, registration);
        try {
            Cancellable cancellable = scheduler.schedule(delay, () -> fire(timer, registration));
            registration.setCancellable(cancellable);
            return generation;
        } catch (RuntimeException exception) {
            registrations.remove(timer, registration);
            throw exception;
        }
    }

    /**
     * Cancels the current generation of a timer.
     *
     * @param timer timer name
     * @return {@code true} when a current registration existed
     */
    public synchronized boolean cancel(SipTimer timer) {
        Registration registration = registrations.remove(Objects.requireNonNull(timer, "timer"));
        if (registration == null) {
            return false;
        }
        registration.cancel();
        return true;
    }

    /**
     * Atomically consumes an expiry only when it is the current generation.
     *
     * @param timer timer name
     * @param generation event generation
     * @return {@code true} when the event is current and consumed
     */
    public synchronized boolean consumeIfCurrent(SipTimer timer, long generation) {
        Registration registration = registrations.get(Objects.requireNonNull(timer, "timer"));
        if (registration == null || registration.generation != generation) {
            return false;
        }
        registrations.remove(timer);
        registration.cancel();
        return true;
    }

    /**
     * Returns the current timer generation.
     *
     * @param timer timer name
     * @return generation, or empty when not scheduled
     */
    public synchronized OptionalLong currentGeneration(SipTimer timer) {
        Registration registration = registrations.get(Objects.requireNonNull(timer, "timer"));
        return registration == null
                ? OptionalLong.empty()
                : OptionalLong.of(registration.generation);
    }

    /**
     * Cancels every current timer and rejects future starts.
     */
    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        registrations.values().forEach(Registration::cancel);
        registrations.clear();
    }

    private void fire(SipTimer timer, Registration expected) {
        synchronized (this) {
            if (closed || registrations.get(timer) != expected) {
                return;
            }
        }
        try {
            eventSink.accept(new TimerExpired(timer, expected.generation));
        } catch (Throwable cause) {
            reportFailure(cause);
        }
    }

    private void reportFailure(Throwable cause) {
        try {
            errorHandler.accept(cause);
        } catch (Throwable errorHandlerFailure) {
            LOGGER.log(System.Logger.Level.WARNING, "transaction timer error handler failed", errorHandlerFailure);
        }
    }

    private static final class Registration {

        private final long generation;
        private Cancellable cancellable;
        private boolean cancellationRequested;

        private Registration(long generation) {
            this.generation = generation;
        }

        private synchronized void setCancellable(Cancellable cancellable) {
            this.cancellable = Objects.requireNonNull(cancellable, "cancellable");
            if (cancellationRequested) {
                cancellable.cancel();
            }
        }

        private synchronized void cancel() {
            cancellationRequested = true;
            if (cancellable != null) {
                cancellable.cancel();
            }
        }
    }
}
