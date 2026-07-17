package org.loomsip.dialog;

import org.loomsip.concurrent.MailboxClosedException;
import org.loomsip.concurrent.SerialMailbox;
import org.loomsip.message.header.CSeqHeaderValue;
import org.loomsip.transaction.tu.TuCallbackDispatcher;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * Serialized mutable core of one SIP Dialog.
 *
 * <pre>{@code
 * Transaction event / Application command
 *                  |
 *                  v
 *          Dialog Mailbox
 *                  |
 *                  v
 * State / CSeq / Remote Target
 *        |                 |
 *        v                 v
 * immutable Snapshot   TU Callback Dispatcher
 * }</pre>
 */
public final class SipDialog implements DialogHandle {

    private static final System.Logger LOGGER = System.getLogger(SipDialog.class.getName());

    private final SerialMailbox<DialogEvent> mailbox;
    private final TuCallbackDispatcher<Runnable> callbacks;
    private final DialogLifecycleListener listener;
    private final Consumer<? super SipDialog> terminationCallback;
    private final CompletableFuture<Void> terminated = new CompletableFuture<>();

    private volatile DialogSnapshot snapshot;
    private boolean terminationStarted;

    SipDialog(
            DialogSnapshot initialSnapshot,
            Executor dialogExecutor,
            Executor callbackExecutor,
            DialogConfig config,
            DialogLifecycleListener listener,
            Consumer<? super SipDialog> terminationCallback
    ) {
        snapshot = Objects.requireNonNull(initialSnapshot, "initialSnapshot");
        this.listener = Objects.requireNonNull(listener, "listener");
        this.terminationCallback = Objects.requireNonNull(terminationCallback, "terminationCallback");
        Objects.requireNonNull(config, "config");
        mailbox = new SerialMailbox<>(
                Objects.requireNonNull(dialogExecutor, "dialogExecutor"),
                this::handleEvent,
                this::handleInfrastructureFailure,
                config.mailboxCapacity()
        );
        callbacks = new TuCallbackDispatcher<>(
                Objects.requireNonNull(callbackExecutor, "callbackExecutor"),
                Runnable::run,
                this::logCallbackFailure,
                config.callbackCapacity()
        );
    }

    @Override
    public DialogId id() {
        return snapshot.id();
    }

    @Override
    public DialogSnapshot snapshot() {
        return snapshot;
    }

    @Override
    public CompletionStage<Void> terminated() {
        return terminated.minimalCompletionStage();
    }

    CompletionStage<Void> transitionTo(DialogState target, DialogTerminationReason reason) {
        CompletableFuture<Void> result = new CompletableFuture<>();
        submit(new DialogStateTransition(target, reason, result), result);
        return result.minimalCompletionStage();
    }

    CompletionStage<Void> updateRemoteTarget(org.loomsip.message.SipUri remoteTarget) {
        CompletableFuture<Void> result = new CompletableFuture<>();
        submit(new DialogRemoteTargetUpdate(remoteTarget, result), result);
        return result.minimalCompletionStage();
    }

    CompletionStage<Long> nextLocalSequence() {
        CompletableFuture<Long> result = new CompletableFuture<>();
        submit(new DialogLocalSequenceRequested(result), result);
        return result.minimalCompletionStage();
    }

    CompletionStage<Void> acceptRemoteSequence(long sequenceNumber) {
        CompletableFuture<Void> result = new CompletableFuture<>();
        submit(new DialogRemoteSequenceReceived(sequenceNumber, result), result);
        return result.minimalCompletionStage();
    }

    void shutdown(DialogTerminationReason reason) {
        try {
            mailbox.submit(new DialogShutdown(reason));
        } catch (MailboxClosedException ignored) {
            // Idempotent shutdown after termination.
        }
    }

    private void handleEvent(DialogEvent event) {
        if (event instanceof DialogStateTransition transition) {
            handleStateTransition(transition);
        } else if (event instanceof DialogRemoteTargetUpdate update) {
            handleRemoteTargetUpdate(update);
        } else if (event instanceof DialogLocalSequenceRequested request) {
            handleLocalSequenceRequest(request);
        } else if (event instanceof DialogRemoteSequenceReceived received) {
            handleRemoteSequence(received);
        } else if (event instanceof DialogShutdown shutdown) {
            terminate(shutdown.reason());
        }
    }

    private void handleStateTransition(DialogStateTransition event) {
        DialogState current = snapshot.state();
        if (current == event.target()) {
            event.result().complete(null);
            return;
        }
        if (!isValidTransition(current, event.target())) {
            event.result().completeExceptionally(new IllegalStateException(
                    "invalid Dialog transition " + current + " -> " + event.target()
            ));
            return;
        }
        if (event.target() == DialogState.CONFIRMED && snapshot.remoteTarget().isEmpty()) {
            event.result().completeExceptionally(new IllegalStateException(
                    "cannot confirm a Dialog without a Remote Target"
            ));
            return;
        }
        updateSnapshot(event.target(), snapshot.localCSeq(), snapshot.remoteCSeq(), snapshot.remoteTarget());
        notifyTu(() -> listener.onStateChanged(this, current, event.target()));
        if (event.target() == DialogState.TERMINATED) {
            terminate(event.terminationReason());
        }
        event.result().complete(null);
    }

    private void handleRemoteTargetUpdate(DialogRemoteTargetUpdate event) {
        if (!ensureActive(event.result())) {
            return;
        }
        updateSnapshot(
                snapshot.state(),
                snapshot.localCSeq(),
                snapshot.remoteCSeq(),
                Optional.of(event.remoteTarget())
        );
        event.result().complete(null);
    }

    private void handleLocalSequenceRequest(DialogLocalSequenceRequested event) {
        if (!ensureActive(event.result())) {
            return;
        }
        if (snapshot.localCSeq() == CSeqHeaderValue.MAX_SEQUENCE_NUMBER) {
            event.result().completeExceptionally(new IllegalStateException("Local CSeq is exhausted"));
            return;
        }
        try {
            long next = Math.incrementExact(snapshot.localCSeq());
            updateSnapshot(snapshot.state(), next, snapshot.remoteCSeq(), snapshot.remoteTarget());
            event.result().complete(next);
        } catch (ArithmeticException exception) {
            event.result().completeExceptionally(exception);
        }
    }

    private void handleRemoteSequence(DialogRemoteSequenceReceived event) {
        if (!ensureActive(event.result())) {
            return;
        }
        if (event.sequenceNumber() <= snapshot.remoteCSeq()) {
            event.result().completeExceptionally(new IllegalArgumentException(
                    "remote CSeq " + event.sequenceNumber()
                            + " is not greater than " + snapshot.remoteCSeq()
            ));
            return;
        }
        updateSnapshot(
                snapshot.state(),
                snapshot.localCSeq(),
                event.sequenceNumber(),
                snapshot.remoteTarget()
        );
        event.result().complete(null);
    }

    private boolean ensureActive(CompletableFuture<?> result) {
        if (snapshot.state() != DialogState.TERMINATED) {
            return true;
        }
        result.completeExceptionally(new IllegalStateException("Dialog is terminated: " + id()));
        return false;
    }

    private void updateSnapshot(
            DialogState state,
            long localCSeq,
            long remoteCSeq,
            Optional<org.loomsip.message.SipUri> remoteTarget
    ) {
        DialogSnapshot current = snapshot;
        snapshot = new DialogSnapshot(
                current.id(),
                current.role(),
                state,
                current.localUri(),
                current.remoteUri(),
                localCSeq,
                remoteCSeq,
                current.routeSet(),
                remoteTarget,
                current.secure()
        );
    }

    private void terminate(DialogTerminationReason reason) {
        if (terminationStarted) {
            return;
        }
        terminationStarted = true;
        DialogState previous = snapshot.state();
        if (previous != DialogState.TERMINATED) {
            updateSnapshot(
                    DialogState.TERMINATED,
                    snapshot.localCSeq(),
                    snapshot.remoteCSeq(),
                    snapshot.remoteTarget()
            );
            notifyTu(() -> listener.onStateChanged(this, previous, DialogState.TERMINATED));
        }
        try {
            terminationCallback.accept(this);
        } catch (Throwable cause) {
            reportFailureDirect(cause);
        }
        notifyTu(() -> listener.onTerminated(this, reason));
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

    private void handleInfrastructureFailure(Throwable cause) {
        reportFailureDirect(cause);
        terminate(DialogTerminationReason.INFRASTRUCTURE_FAILURE);
    }

    private void notifyTu(Runnable callback) {
        try {
            callbacks.dispatch(callback);
        } catch (Throwable cause) {
            logCallbackFailure(cause);
        }
    }

    private void reportFailureDirect(Throwable cause) {
        try {
            listener.onFailure(this, cause);
        } catch (Throwable listenerFailure) {
            logCallbackFailure(listenerFailure);
        }
    }

    private void logCallbackFailure(Throwable cause) {
        LOGGER.log(System.Logger.Level.WARNING, "Dialog callback failed", cause);
    }

    private void submit(DialogEvent event, CompletableFuture<?> result) {
        try {
            mailbox.submit(event);
        } catch (Throwable cause) {
            result.completeExceptionally(cause);
        }
    }

    private static boolean isValidTransition(DialogState current, DialogState target) {
        return switch (current) {
            case EARLY -> target == DialogState.CONFIRMED || target == DialogState.TERMINATED;
            case CONFIRMED -> target == DialogState.TERMINATED;
            case TERMINATED -> false;
        };
    }
}
