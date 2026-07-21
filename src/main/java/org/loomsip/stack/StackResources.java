package org.loomsip.stack;

import org.loomsip.transaction.timer.DefaultSipScheduler;
import org.loomsip.transaction.timer.SipScheduler;

import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Executor and timer resources used by one Stack, with explicit ownership.
 *
 * <pre>{@code
 * LoomSipStack --> StackResources
 *                     |        |
 *                     v        v
 *             callback executor scheduler
 * }</pre>
 *
 * <p>Externally supplied resources remain caller-owned unless the caller uses
 * {@link #owned(ExecutorService, SipScheduler)}. Stack close only releases
 * resources marked owned.</p>
 */
public final class StackResources implements AutoCloseable {

    private final ExecutorService callbackExecutor;
    private final SipScheduler scheduler;
    private final boolean ownsCallbackExecutor;
    private final boolean ownsScheduler;
    private final AtomicBoolean closed = new AtomicBoolean();

    private StackResources(
            ExecutorService callbackExecutor,
            SipScheduler scheduler,
            boolean ownsCallbackExecutor,
            boolean ownsScheduler
    ) {
        this.callbackExecutor = Objects.requireNonNull(callbackExecutor, "callbackExecutor");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.ownsCallbackExecutor = ownsCallbackExecutor;
        this.ownsScheduler = ownsScheduler;
    }

    /**
     * Creates Stack-owned default resources.
     *
     * @return resources using virtual threads for callbacks and one SIP timer scheduler
     */
    public static StackResources createDefault() {
        return owned(
                Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("loomsip-stack-callback-", 0).factory()),
                new DefaultSipScheduler()
        );
    }

    /**
     * Creates resources owned and closed by the Stack.
     *
     * @param callbackExecutor protocol callback executor
     * @param scheduler SIP timer scheduler
     * @return resources transferred to Stack ownership
     */
    public static StackResources owned(ExecutorService callbackExecutor, SipScheduler scheduler) {
        return new StackResources(callbackExecutor, scheduler, true, true);
    }

    /**
     * Creates externally owned resources which Stack may use but never closes.
     *
     * @param callbackExecutor protocol callback executor
     * @param scheduler SIP timer scheduler
     * @return resources retained by the caller
     */
    public static StackResources external(ExecutorService callbackExecutor, SipScheduler scheduler) {
        return new StackResources(callbackExecutor, scheduler, false, false);
    }

    /**
     * Returns the executor on which Stack-owned protocol callbacks will run.
     *
     * @return callback executor
     */
    public ExecutorService callbackExecutor() {
        return callbackExecutor;
    }

    /**
     * Returns the scheduler used by Stack-owned protocol timers.
     *
     * @return SIP scheduler
     */
    public SipScheduler scheduler() {
        return scheduler;
    }

    /**
     * Reports whether Stack owns the callback executor.
     *
     * @return whether close shuts down the executor
     */
    public boolean ownsCallbackExecutor() {
        return ownsCallbackExecutor;
    }

    /**
     * Reports whether Stack owns the timer scheduler.
     *
     * @return whether close shuts down the scheduler
     */
    public boolean ownsScheduler() {
        return ownsScheduler;
    }

    /**
     * Closes only resources transferred to Stack ownership.
     *
     * <p>The callback executor is interrupted to release virtual-thread tasks
     * that outlived protocol shutdown. External resources are intentionally
     * untouched.</p>
     */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        RuntimeException failure = null;
        if (ownsScheduler) {
            try {
                scheduler.close();
            } catch (RuntimeException exception) {
                failure = exception;
            }
        }
        if (ownsCallbackExecutor) {
            callbackExecutor.shutdownNow();
        }
        if (failure != null) {
            throw failure;
        }
    }

    /**
     * Waits for Stack-owned callback tasks to terminate after {@link #close()}.
     *
     * @param timeout maximum wait time
     * @return whether the owned executor terminated before the timeout
     * @throws InterruptedException if the calling thread is interrupted
     */
    boolean awaitOwnedExecutorTermination(java.time.Duration timeout) throws InterruptedException {
        Objects.requireNonNull(timeout, "timeout");
        return !ownsCallbackExecutor || callbackExecutor.awaitTermination(timeout.toNanos(), TimeUnit.NANOSECONDS);
    }
}
