package org.loomsip.transaction.timer;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Single-platform-thread production scheduler for SIP timer callbacks.
 */
public final class DefaultSipScheduler implements SipScheduler {

    private final ScheduledThreadPoolExecutor executor;
    private final AtomicBoolean closed = new AtomicBoolean();

    /**
     * Creates a daemon scheduler thread named {@code loomsip-timer}.
     */
    public DefaultSipScheduler() {
        ThreadFactory threadFactory = runnable -> {
            Thread thread = Thread.ofPlatform().daemon().name("loomsip-timer").unstarted(runnable);
            thread.setUncaughtExceptionHandler((ignored, cause) ->
                    System.getLogger(DefaultSipScheduler.class.getName()).log(
                            System.Logger.Level.WARNING,
                            "uncaught SIP scheduler failure",
                            cause
                    ));
            return thread;
        };
        executor = new ScheduledThreadPoolExecutor(1, threadFactory);
        executor.setRemoveOnCancelPolicy(true);
        executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        executor.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
    }

    @Override
    public Cancellable schedule(Duration delay, Runnable callback) {
        Objects.requireNonNull(delay, "delay");
        Objects.requireNonNull(callback, "callback");
        if (delay.isNegative()) {
            throw new IllegalArgumentException("scheduler delay must not be negative");
        }
        if (closed.get()) {
            throw new IllegalStateException("SIP scheduler is closed");
        }
        final ScheduledFuture<?> future;
        try {
            future = executor.schedule(callback, delay.toNanos(), TimeUnit.NANOSECONDS);
        } catch (RejectedExecutionException exception) {
            throw new IllegalStateException("SIP scheduler is closed", exception);
        }
        return new FutureCancellable(future);
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            executor.shutdownNow();
        }
    }

    private record FutureCancellable(ScheduledFuture<?> future) implements Cancellable {

        private FutureCancellable {
            Objects.requireNonNull(future, "future");
        }

        @Override
        public boolean cancel() {
            return future.cancel(false);
        }

        @Override
        public boolean isCancelled() {
            return future.isCancelled();
        }
    }
}
