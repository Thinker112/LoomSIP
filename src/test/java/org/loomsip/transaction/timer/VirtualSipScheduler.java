package org.loomsip.transaction.timer;

import java.time.Duration;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Deterministic scheduler used by transaction timer tests without real waiting.
 */
public final class VirtualSipScheduler implements SipScheduler {

    private final PriorityQueue<ScheduledTask> tasks = new PriorityQueue<>();
    private long nowNanos;
    private long nextSequence;
    private boolean closed;

    /** Creates a scheduler whose virtual clock starts at zero. */
    public VirtualSipScheduler() {
    }

    @Override
    public synchronized Cancellable schedule(Duration delay, Runnable callback) {
        Objects.requireNonNull(delay, "delay");
        Objects.requireNonNull(callback, "callback");
        if (delay.isNegative()) {
            throw new IllegalArgumentException("delay must not be negative");
        }
        if (closed) {
            throw new IllegalStateException("virtual scheduler is closed");
        }
        ScheduledTask task = new ScheduledTask(
                Math.addExact(nowNanos, delay.toNanos()),
                nextSequence++,
                callback
        );
        tasks.add(task);
        return task;
    }

    public void advanceBy(Duration duration) {
        Objects.requireNonNull(duration, "duration");
        if (duration.isNegative()) {
            throw new IllegalArgumentException("advance duration must not be negative");
        }
        long target;
        synchronized (this) {
            target = Math.addExact(nowNanos, duration.toNanos());
        }
        while (true) {
            ScheduledTask task;
            synchronized (this) {
                task = tasks.peek();
                if (task == null || task.dueNanos > target) {
                    nowNanos = target;
                    return;
                }
                tasks.remove();
                nowNanos = task.dueNanos;
            }
            if (!task.isCancelled()) {
                task.callback.run();
            }
        }
    }

    public synchronized int pendingCount() {
        return tasks.size();
    }

    @Override
    public synchronized void close() {
        closed = true;
        tasks.clear();
    }

    private static final class ScheduledTask implements Cancellable, Comparable<ScheduledTask> {

        private final long dueNanos;
        private final long sequence;
        private final Runnable callback;
        private final AtomicBoolean cancelled = new AtomicBoolean();

        private ScheduledTask(long dueNanos, long sequence, Runnable callback) {
            this.dueNanos = dueNanos;
            this.sequence = sequence;
            this.callback = callback;
        }

        @Override
        public boolean cancel() {
            return cancelled.compareAndSet(false, true);
        }

        @Override
        public boolean isCancelled() {
            return cancelled.get();
        }

        @Override
        public int compareTo(ScheduledTask other) {
            int dueComparison = Long.compare(dueNanos, other.dueNanos);
            return dueComparison != 0 ? dueComparison : Long.compare(sequence, other.sequence);
        }
    }
}
