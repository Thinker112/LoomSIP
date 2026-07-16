package org.loomsip.transaction.tu;

import org.loomsip.concurrent.MailboxState;
import org.loomsip.concurrent.SerialMailbox;

import java.util.Objects;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * Serial callback queue that keeps potentially blocking TU code off a transaction mailbox.
 *
 * <p>One dispatcher is normally associated with one transaction. Notifications
 * retain order while different transactions may use the same virtual-thread
 * executor concurrently.</p>
 *
 * <pre>{@code
 * Transaction State Machine
 *           |
 *           | TU notification
 *           v
 * +----------------------+
 * | TuCallbackDispatcher |
 * | SerialMailbox        |
 * +----------+-----------+
 *            |
 *            v
 *  Virtual-thread callback
 *            |
 *            v
 *      TU / Application
 *            |
 *            | command/response
 *            v
 *   Transaction Mailbox
 * }</pre>
 *
 * @param <E> immutable TU notification type
 */
public final class TuCallbackDispatcher<E> implements AutoCloseable {

    private final SerialMailbox<E> mailbox;

    /**
     * Creates an ordered TU callback dispatcher.
     *
     * @param executor shared callback executor, normally backed by virtual threads
     * @param callback transaction-user callback
     * @param errorHandler callback failure consumer
     * @param capacity maximum queued notifications
     */
    public TuCallbackDispatcher(
            Executor executor,
            Consumer<? super E> callback,
            Consumer<? super Throwable> errorHandler,
            int capacity
    ) {
        mailbox = new SerialMailbox<>(
                Objects.requireNonNull(executor, "executor"),
                Objects.requireNonNull(callback, "callback"),
                Objects.requireNonNull(errorHandler, "errorHandler"),
                capacity
        );
    }

    /**
     * Queues one ordered TU notification for execution by the configured executor.
     *
     * @param event notification to deliver
     */
    public void dispatch(E event) {
        mailbox.submit(event);
    }

    /**
     * Returns the current callback queue state.
     *
     * @return mailbox state
     */
    public MailboxState state() {
        return mailbox.state();
    }

    /**
     * Returns a stage completed after accepted callbacks finish during close.
     *
     * @return close completion
     */
    public CompletionStage<Void> closed() {
        return mailbox.closed();
    }

    /**
     * Rejects new notifications and drains all accepted callbacks.
     */
    @Override
    public void close() {
        mailbox.close();
    }

    /**
     * Rejects new notifications and discards callbacks that have not started.
     */
    public void closeNow() {
        mailbox.closeNow();
    }
}
