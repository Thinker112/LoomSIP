package org.loomsip.transaction.noninvite;

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
 * Shared asynchronous boundaries for the two Non-INVITE state machines.
 *
 * <pre>{@code
 * Network / Timer / TU command
 *             |
 *             v
 *     Transaction Mailbox
 *             |
 *             v
 *       concrete NICT/NIST
 *        |            |
 *        v            v
 * MessageSender   TU Callback Dispatcher
 *        |            |
 *        v            v
 * Transport Event   Application
 *        |
 *        +----------> Mailbox
 * }</pre>
 */
abstract class AbstractNonInviteTransaction implements SipTransaction {

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

    AbstractNonInviteTransaction(
            TransactionKey key,
            TransactionMessageSender sender,
            SipScheduler scheduler,
            Executor transactionExecutor,
            Executor callbackExecutor,
            NonInviteTransactionConfig config,
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
        Objects.requireNonNull(config, "config");
        mailbox = new SerialMailbox<>(
                Objects.requireNonNull(transactionExecutor, "transactionExecutor"),
                this::handleEvent,
                this::handleInfrastructureFailure,
                config.mailboxCapacity()
        );
        callbacks = new TuCallbackDispatcher<>(
                Objects.requireNonNull(callbackExecutor, "callbackExecutor"),
                Runnable::run,
                this::reportInfrastructureFailure,
                config.callbackCapacity()
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

    final CompletionStage<Void> terminationStage() {
        return terminated.minimalCompletionStage();
    }

    final TransactionTimerManager timers() {
        return timers;
    }

    final void submit(TransactionEvent event) {
        mailbox.submit(event);
    }

    final void notifyTu(Runnable callback) {
        callbacks.dispatch(callback);
    }

    final long sendMessage(SipMessage message, TransportEndpoint target) {
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

    final void terminate(Runnable finalNotification) {
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

    final void reportInfrastructureFailure(Throwable cause) {
        try {
            infrastructureErrorHandler.accept(cause);
        } catch (Throwable ignored) {
            System.getLogger(getClass().getName()).log(
                    System.Logger.Level.WARNING,
                    "Non-INVITE infrastructure error handler failed",
                    ignored
            );
        }
    }

    abstract void handleEvent(TransactionEvent event);

    abstract void handleInfrastructureFailure(Throwable cause);

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
