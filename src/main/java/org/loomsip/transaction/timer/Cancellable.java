package org.loomsip.transaction.timer;

/**
 * Handle for best-effort cancellation of scheduled work.
 */
public interface Cancellable {

    /**
     * Attempts to prevent callback execution.
     *
     * @return {@code true} when this invocation cancelled pending work
     */
    boolean cancel();

    /**
     * Indicates whether cancellation was requested successfully.
     *
     * @return cancellation state
     */
    boolean isCancelled();
}
