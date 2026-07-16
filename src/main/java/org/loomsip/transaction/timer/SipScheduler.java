package org.loomsip.transaction.timer;

import java.time.Duration;

/**
 * Scheduler used only to enqueue transaction timer callbacks.
 *
 * <p>Callbacks must remain short and must not execute state machines or TU code.</p>
 *
 * <pre>{@code
 * TransactionTimerManager
 *          |
 *          | schedule(delay, callback)
 *          v
 * +----------------+
 * | SipScheduler   |
 * | platform timer |
 * +-------+--------+
 *         |
 *         | delay elapsed
 *         v
 * Short callback --> Mailbox event
 * }</pre>
 */
public interface SipScheduler extends AutoCloseable {

    /**
     * Schedules one callback after the specified delay.
     *
     * @param delay non-negative relative delay
     * @param callback short callback that normally submits a mailbox event
     * @return cancellation handle
     * @throws IllegalArgumentException if delay is negative
     * @throws IllegalStateException if the scheduler is closed
     */
    Cancellable schedule(Duration delay, Runnable callback);

    /**
     * Cancels pending callbacks and releases scheduler resources.
     */
    @Override
    void close();
}
