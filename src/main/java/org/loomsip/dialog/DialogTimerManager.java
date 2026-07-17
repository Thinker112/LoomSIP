package org.loomsip.dialog;

import org.loomsip.transaction.timer.Cancellable;
import org.loomsip.transaction.timer.SipScheduler;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Allocates timer generations for all pending INVITE exchanges in one Dialog.
 *
 * <pre>{@code
 * Dialog Mailbox -- start/cancel --> DialogTimerManager
 *                                      |
 *                                      v
 *                                 SipScheduler
 *                                      |
 *                                      v
 *                            DialogTimerExpired event
 *                                      |
 *                                      v
 *                                Dialog Mailbox
 * }</pre>
 */
final class DialogTimerManager implements AutoCloseable {

    private final SipScheduler scheduler;
    private final Consumer<? super DialogTimerExpired> eventSink;
    private final Consumer<? super Throwable> errorHandler;
    private final Map<DialogTimerKey, Registration> registrations = new HashMap<>();

    private long nextGeneration;
    private boolean closed;

    DialogTimerManager(
            SipScheduler scheduler,
            Consumer<? super DialogTimerExpired> eventSink,
            Consumer<? super Throwable> errorHandler
    ) {
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.eventSink = Objects.requireNonNull(eventSink, "eventSink");
        this.errorHandler = Objects.requireNonNull(errorHandler, "errorHandler");
    }

    synchronized long start(DialogTimerKey key, Duration delay) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(delay, "delay");
        if (closed) {
            throw new IllegalStateException("Dialog timer manager is closed");
        }
        Registration previous = registrations.remove(key);
        if (previous != null) {
            previous.cancel();
        }
        long generation = ++nextGeneration;
        Registration registration = new Registration(generation);
        registrations.put(key, registration);
        try {
            registration.setCancellable(scheduler.schedule(delay, () -> fire(key, registration)));
            return generation;
        } catch (RuntimeException exception) {
            registrations.remove(key, registration);
            throw exception;
        }
    }

    synchronized boolean cancel(DialogTimerKey key) {
        Registration registration = registrations.remove(Objects.requireNonNull(key, "key"));
        if (registration == null) {
            return false;
        }
        registration.cancel();
        return true;
    }

    synchronized boolean consumeIfCurrent(DialogTimerKey key, long generation) {
        Registration registration = registrations.get(Objects.requireNonNull(key, "key"));
        if (registration == null || registration.generation != generation) {
            return false;
        }
        registrations.remove(key);
        registration.cancel();
        return true;
    }

    synchronized void cancelInvite(DialogInviteKey invite) {
        registrations.entrySet().removeIf(entry -> {
            if (!entry.getKey().invite().equals(invite)) {
                return false;
            }
            entry.getValue().cancel();
            return true;
        });
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        registrations.values().forEach(Registration::cancel);
        registrations.clear();
    }

    private void fire(DialogTimerKey key, Registration expected) {
        synchronized (this) {
            if (closed || registrations.get(key) != expected) {
                return;
            }
        }
        try {
            eventSink.accept(new DialogTimerExpired(key, expected.generation));
        } catch (Throwable cause) {
            try {
                errorHandler.accept(cause);
            } catch (Throwable ignored) {
                System.getLogger(getClass().getName()).log(
                        System.Logger.Level.WARNING,
                        "Dialog timer error handler failed",
                        ignored
                );
            }
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
