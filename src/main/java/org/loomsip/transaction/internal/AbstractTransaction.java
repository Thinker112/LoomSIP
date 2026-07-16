package org.loomsip.transaction.internal;

import org.loomsip.concurrent.MailboxClosedException;
import org.loomsip.concurrent.SerialMailbox;
import org.loomsip.message.SipMessage;
import org.loomsip.transaction.SipTransaction;
import org.loomsip.transaction.TransactionKey;
import org.loomsip.transaction.TransactionMessageSender;
import org.loomsip.transaction.event.TransactionEvent;
import org.loomsip.transaction.event.TransportFailed;
import org.loomsip.transaction.event.TransportSucceeded;
import org.loomsip.transaction.timer.SipScheduler;
import org.loomsip.transaction.timer.TransactionTimerManager;
import org.loomsip.transaction.tu.TuCallbackDispatcher;
import org.loomsip.transport.SendResult;
import org.loomsip.transport.TransportEndpoint;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Shared serialized execution and asynchronous lifecycle for SIP transactions.
 *
 * <p>This type is public only so transaction implementations in sibling packages
 * can extend it. Applications should use the transaction handles exposed by a
 * concrete transaction manager.</p>
 *
 * <pre>{@code
 * Network / Timer / TU command
 *             |
 *             v
 *     Transaction Mailbox
 *             |
 *             v
 *       State Machine
 *        |          |
 *        v          v
 * MessageSender   TU Callback Dispatcher
 *        |          |
 *        v          v
 * Transport Event  Application
 *        |
 *        +--------> Mailbox
 * }</pre>
 */
public abstract class AbstractTransaction implements SipTransaction {

    private final TransactionKey key;
    private final TransactionMessageSender sender;
    private final SerialMailbox<TransactionEvent> mailbox;
    private final TransactionTimerManager timers;
    private final TuCallbackDispatcher<Runnable> callbacks;
    private final Consumer<? super SipTransaction> terminationCallback;
    private final Consumer<? super Throwable> infrastructureErrorHandler;
    private final AtomicLong nextOperationId = new AtomicLong();
    private final CompletableFuture<Void> terminated = new CompletableFuture<>();

    private boolean terminationStarted;

    /**
     * Creates the asynchronous boundaries owned by one transaction.
     *
     * @param key transaction identity
     * @param sender transport write boundary
     * @param scheduler shared SIP timer scheduler
     * @param transactionExecutor state-machine executor
     * @param callbackExecutor TU callback executor
     * @param mailboxCapacity maximum queued state-machine events
     * @param callbackCapacity maximum queued TU callbacks
     * @param terminationCallback repository cleanup callback
     * @param infrastructureErrorHandler infrastructure failure callback
     */
    protected AbstractTransaction(
            TransactionKey key,
            TransactionMessageSender sender,
            SipScheduler scheduler,
            Executor transactionExecutor,
            Executor callbackExecutor,
            int mailboxCapacity,
            int callbackCapacity,
            Consumer<? super SipTransaction> terminationCallback,
            Consumer<? super Throwable> infrastructureErrorHandler
    ) {
        this.key = Objects.requireNonNull(key, "key");
        this.sender = Objects.requireNonNull(sender, "sender");
        this.terminationCallback = Objects.requireNonNull(terminationCallback, "terminationCallback");
        this.infrastructureErrorHandler = Objects.requireNonNull(
                infrastructureErrorHandler,
                "infrastructureErrorHandler"
        );
        mailbox = new SerialMailbox<>(
                Objects.requireNonNull(transactionExecutor, "transactionExecutor"),
                this::handleEvent,
                this::handleInfrastructureFailure,
                mailboxCapacity
        );
        callbacks = new TuCallbackDispatcher<>(
                Objects.requireNonNull(callbackExecutor, "callbackExecutor"),
                Runnable::run,
                this::reportInfrastructureFailure,
                callbackCapacity
        );
        timers = new TransactionTimerManager(
                Objects.requireNonNull(scheduler, "scheduler"),
                this::submitSafely,
                this::handleInfrastructureFailure
        );
    }

    @Override
    public final TransactionKey key() {
        return key;
    }

    /**
     * Returns the transaction termination stage.
     *
     * @return completion after mailbox and TU callback drainage
     */
    protected final CompletionStage<Void> terminationStage() {
        return terminated.minimalCompletionStage();
    }

    /**
     * Returns this transaction's timer manager.
     *
     * @return owned timer registrations
     */
    protected final TransactionTimerManager timers() {
        return timers;
    }

    /**
     * Submits an event to the serialized state machine.
     *
     * @param event immutable transaction event
     */
    protected final void submit(TransactionEvent event) {
        mailbox.submit(event);
    }

    /**
     * Queues an ordered callback outside the state-machine mailbox.
     *
     * @param callback TU notification
     */
    protected final void notifyTu(Runnable callback) {
        callbacks.dispatch(callback);
    }

    /**
     * Starts one asynchronous transport operation.
     *
     * @param message immutable SIP message
     * @param target selected remote endpoint
     * @return operation identifier carried by its completion event
     */
    protected final long sendMessage(SipMessage message, TransportEndpoint target) {
        long operationId = nextOperationId.incrementAndGet();
        final CompletionStage<SendResult> sendStage;
        try {
            sendStage = Objects.requireNonNull(sender.send(message, target), "sender result");
        } catch (Throwable cause) {
            submitSafely(new TransportFailed(operationId, cause));
            return operationId;
        }
        sendStage.whenComplete((result, failure) -> {
            if (failure == null) {
                submitSafely(new TransportSucceeded(operationId, result));
            } else {
                submitSafely(new TransportFailed(operationId, unwrapCompletionFailure(failure)));
            }
        });
        return operationId;
    }

    /**
     * Terminates the transaction after draining accepted TU notifications.
     *
     * @param finalNotification final ordered callback, or {@code null}
     */
    protected final void terminate(Runnable finalNotification) {
        if (terminationStarted) {
            return;
        }
        terminationStarted = true;
        timers.close();
        try {
            terminationCallback.accept(this);
        } catch (Throwable cause) {
            reportInfrastructureFailure(cause);
        }
        if (finalNotification != null) {
            try {
                notifyTu(finalNotification);
            } catch (Throwable cause) {
                reportInfrastructureFailure(cause);
            }
        }
        callbacks.close();
        mailbox.close();

        CompletableFuture<Void> mailboxClosed = mailbox.closed().toCompletableFuture();
        CompletableFuture<Void> callbacksClosed = callbacks.closed().toCompletableFuture();
        CompletableFuture.allOf(mailboxClosed, callbacksClosed).whenComplete((ignored, failure) -> {
            if (failure == null) {
                terminated.complete(null);
            } else {
                terminated.completeExceptionally(failure);
            }
        });
    }

    /**
     * Reports an infrastructure failure without allowing the observer to escape.
     *
     * @param cause infrastructure failure
     */
    protected final void reportInfrastructureFailure(Throwable cause) {
        try {
            infrastructureErrorHandler.accept(cause);
        } catch (Throwable ignored) {
            System.getLogger(getClass().getName()).log(
                    System.Logger.Level.WARNING,
                    "transaction infrastructure error handler failed",
                    ignored
            );
        }
    }

    /**
     * Handles one event on the transaction mailbox.
     *
     * @param event next serialized event
     */
    protected abstract void handleEvent(TransactionEvent event);

    /**
     * Handles a mailbox, scheduler, or callback infrastructure failure.
     *
     * @param cause infrastructure failure
     */
    protected abstract void handleInfrastructureFailure(Throwable cause);

    private void submitSafely(TransactionEvent event) {
        try {
            mailbox.submit(event);
        } catch (MailboxClosedException ignored) {
            // Async completion after transaction termination is expected.
        } catch (Throwable cause) {
            handleInfrastructureFailure(cause);
        }
    }

    private static Throwable unwrapCompletionFailure(Throwable failure) {
        if ((failure instanceof java.util.concurrent.CompletionException
                || failure instanceof java.util.concurrent.ExecutionException)
                && failure.getCause() != null) {
            return failure.getCause();
        }
        return failure;
    }
}
