package org.loomsip.concurrent;

import java.util.ArrayDeque;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * Capacity-limited FIFO mailbox with at most one active drain task.
 *
 * <p>Submitters may run concurrently. Events are handled serially in acceptance
 * order on the supplied executor. The mailbox does not own or close that
 * executor and does not reserve a thread while its queue is empty.</p>
 *
 * <pre>{@code
 * Mailbox Queue
 * +----------------+
 * | Event A        |
 * | Event B        |
 * | Event C        |
 * +-------+--------+
 *         |
 *         v
 *     Drain Task
 *         |
 *         +--> eventHandler.accept(A)
 *         +--> eventHandler.accept(B)
 *         +--> eventHandler.accept(C)
 *         |
 *         v
 * Queue empty: drain task ends and releases its virtual thread
 * }</pre>
 *
 * @param <E> immutable event type
 */
public final class SerialMailbox<E> implements AutoCloseable {

    private static final System.Logger LOGGER = System.getLogger(SerialMailbox.class.getName());

    private final Object monitor = new Object();
    private final ArrayDeque<E> queue = new ArrayDeque<>();
    private final Executor executor;
    private final Consumer<? super E> eventHandler;
    private final Consumer<? super Throwable> errorHandler;
    private final int capacity;
    private final CompletableFuture<Void> closed = new CompletableFuture<>();

    private MailboxState state = MailboxState.OPEN;
    private boolean draining;

    /**
     * Creates an open serial mailbox.
     *
     * @param executor executor used only while events are available
     * @param eventHandler serial event consumer
     * @param errorHandler receives event-handler and executor failures
     * @param capacity maximum number of queued events, excluding the currently handled event
     * @throws NullPointerException if an object argument is {@code null}
     * @throws IllegalArgumentException if capacity is not positive
     */
    public SerialMailbox(
            Executor executor,
            Consumer<? super E> eventHandler,
            Consumer<? super Throwable> errorHandler,
            int capacity
    ) {
        this.executor = Objects.requireNonNull(executor, "executor");
        this.eventHandler = Objects.requireNonNull(eventHandler, "eventHandler");
        this.errorHandler = Objects.requireNonNull(errorHandler, "errorHandler");
        if (capacity <= 0) {
            throw new IllegalArgumentException("mailbox capacity must be positive");
        }
        this.capacity = capacity;
    }

    /**
     * Accepts an event and schedules a drain task when needed.
     *
     * @param event event to process
     * @throws NullPointerException if {@code event} is {@code null}
     * @throws MailboxClosedException if shutdown has begun
     * @throws MailboxFullException if the pending queue is full
     * @throws RuntimeException if the executor rejects the newly required drain task
     */
    public void submit(E event) {
        Objects.requireNonNull(event, "event");
        synchronized (monitor) {
            if (state != MailboxState.OPEN) {
                throw new MailboxClosedException(state);
            }
            if (queue.size() >= capacity) {
                throw new MailboxFullException(capacity);
            }
            queue.addLast(event);
            if (!draining) {
                draining = true;
                try {
                    executor.execute(this::drain);
                } catch (RuntimeException exception) {
                    draining = false;
                    queue.clear();
                    state = MailboxState.CLOSED;
                    closed.completeExceptionally(exception);
                    reportFailure(exception);
                    throw exception;
                }
            }
        }
    }

    /**
     * Returns the current lifecycle state.
     *
     * @return mailbox state
     */
    public MailboxState state() {
        synchronized (monitor) {
            return state;
        }
    }

    /**
     * Returns the number of accepted events still waiting in the queue.
     *
     * @return pending event count, excluding an event currently being handled
     */
    public int pendingCount() {
        synchronized (monitor) {
            return queue.size();
        }
    }

    /**
     * Returns a stage completed when the mailbox reaches CLOSED.
     *
     * @return close completion stage
     */
    public CompletionStage<Void> closed() {
        return closed.minimalCompletionStage();
    }

    /**
     * Rejects new events and closes after all accepted events are processed.
     */
    @Override
    public void close() {
        synchronized (monitor) {
            if (state == MailboxState.CLOSED || state == MailboxState.CLOSING) {
                return;
            }
            state = MailboxState.CLOSING;
            completeCloseIfDrained();
        }
    }

    /**
     * Rejects new events, discards queued events, and closes after any currently
     * executing handler returns.
     */
    public void closeNow() {
        synchronized (monitor) {
            if (state == MailboxState.CLOSED) {
                return;
            }
            state = MailboxState.CLOSING;
            queue.clear();
            completeCloseIfDrained();
        }
    }

    private void drain() {
        while (true) {
            E event;
            synchronized (monitor) {
                event = queue.pollFirst();
                if (event == null) {
                    draining = false;
                    completeCloseIfDrained();
                    return;
                }
            }
            try {
                eventHandler.accept(event);
            } catch (Throwable cause) {
                reportFailure(cause);
            }
        }
    }

    private void completeCloseIfDrained() {
        if (state == MailboxState.CLOSING && !draining && queue.isEmpty()) {
            state = MailboxState.CLOSED;
            closed.complete(null);
        }
    }

    private void reportFailure(Throwable cause) {
        try {
            errorHandler.accept(cause);
        } catch (Throwable errorHandlerFailure) {
            LOGGER.log(System.Logger.Level.WARNING, "mailbox error handler failed", errorHandlerFailure);
        }
    }
}
