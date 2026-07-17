package org.loomsip.dialog;

import org.loomsip.message.SipUri;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Creation, lookup, command routing, and lifecycle owner for SIP Dialogs.
 *
 * <pre>{@code
 * Dialog creation / command
 *           |
 *           v
 * +-------------------+
 * |   DialogManager   |
 * | repository lookup |
 * +---------+---------+
 *           |
 *           v
 *       SipDialog
 *           |
 *           v
 *    Dialog Mailbox
 * }</pre>
 */
public final class DialogManager implements AutoCloseable {

    private static final Duration CLOSE_TIMEOUT = Duration.ofSeconds(5);

    private final DialogConfig config;
    private final DialogLifecycleListener listener;
    private final DialogRepository repository;
    private final Executor dialogExecutor;
    private final Executor callbackExecutor;
    private final ExecutorService ownedDialogExecutor;
    private final ExecutorService ownedCallbackExecutor;
    private final boolean ownsExecutors;
    private final Object lifecycleMonitor = new Object();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final CompletableFuture<Void> activeDialogsStopped = new CompletableFuture<>();

    private int dialogsStopping;
    private Throwable dialogStopFailure;

    /**
     * Creates a manager that owns virtual-thread Dialog and callback executors.
     *
     * @param config Dialog capacities
     * @param listener ordered lifecycle listener
     */
    public DialogManager(DialogConfig config, DialogLifecycleListener listener) {
        this(
                config,
                listener,
                new InMemoryDialogRepository(config.dialogs()),
                newVirtualExecutor("loomsip-dialog-"),
                newVirtualExecutor("loomsip-dialog-tu-"),
                true
        );
    }

    /**
     * Creates a manager using externally owned repository and executors.
     *
     * @param config Dialog capacities
     * @param listener ordered lifecycle listener
     * @param repository externally owned Dialog repository
     * @param dialogExecutor state executor
     * @param callbackExecutor TU callback executor
     */
    public DialogManager(
            DialogConfig config,
            DialogLifecycleListener listener,
            DialogRepository repository,
            Executor dialogExecutor,
            Executor callbackExecutor
    ) {
        this(config, listener, repository, dialogExecutor, callbackExecutor, false);
    }

    private DialogManager(
            DialogConfig config,
            DialogLifecycleListener listener,
            DialogRepository repository,
            Executor dialogExecutor,
            Executor callbackExecutor,
            boolean ownsExecutors
    ) {
        this.config = Objects.requireNonNull(config, "config");
        this.listener = Objects.requireNonNull(listener, "listener");
        this.repository = Objects.requireNonNull(repository, "repository");
        this.dialogExecutor = Objects.requireNonNull(dialogExecutor, "dialogExecutor");
        this.callbackExecutor = Objects.requireNonNull(callbackExecutor, "callbackExecutor");
        if (repository.capacity() < config.dialogs()) {
            throw new IllegalArgumentException("repository capacity is below Dialog configuration");
        }
        this.ownsExecutors = ownsExecutors;
        ownedDialogExecutor = ownsExecutors ? (ExecutorService) dialogExecutor : null;
        ownedCallbackExecutor = ownsExecutors ? (ExecutorService) callbackExecutor : null;
    }

    /**
     * Atomically creates or returns a Dialog with the supplied identity.
     *
     * @param initialSnapshot initial immutable state
     * @return active Dialog handle
     */
    public DialogHandle create(DialogSnapshot initialSnapshot) {
        Objects.requireNonNull(initialSnapshot, "initialSnapshot");
        synchronized (lifecycleMonitor) {
            ensureOpen();
            return repository.getOrCreate(initialSnapshot.id(), () -> new SipDialog(
                    initialSnapshot,
                    dialogExecutor,
                    callbackExecutor,
                    config,
                    listener,
                    this::dialogTerminated
            ));
        }
    }

    /**
     * Finds an active Dialog by its full identity.
     *
     * @param id Dialog ID
     * @return active Dialog handle
     */
    public Optional<DialogHandle> find(DialogId id) {
        return repository.find(Objects.requireNonNull(id, "id")).map(dialog -> dialog);
    }

    /**
     * Returns active fork-related Dialogs.
     *
     * @param setId Dialog Set identity
     * @return immutable handles
     */
    public List<DialogHandle> findBySet(DialogSetId setId) {
        return repository.findBySet(Objects.requireNonNull(setId, "setId"))
                .stream()
                .map(dialog -> (DialogHandle) dialog)
                .toList();
    }

    /**
     * Returns the current number of active Dialogs.
     *
     * @return active Dialog count
     */
    public int activeDialogs() {
        return repository.size();
    }

    /**
     * Submits a lifecycle transition to the selected Dialog Mailbox.
     *
     * @param id Dialog identity
     * @param target requested state
     * @param reason reason used if the transition terminates the Dialog
     * @return transition completion
     */
    java.util.concurrent.CompletionStage<Void> transition(
            DialogId id,
            DialogState target,
            DialogTerminationReason reason
    ) {
        return requireDialog(id).transitionTo(target, reason);
    }

    /**
     * Submits a Remote Target update to the selected Dialog Mailbox.
     *
     * @param id Dialog identity
     * @param remoteTarget replacement Contact URI
     * @return update completion
     */
    java.util.concurrent.CompletionStage<Void> updateRemoteTarget(DialogId id, SipUri remoteTarget) {
        return requireDialog(id).updateRemoteTarget(remoteTarget);
    }

    /**
     * Atomically allocates the next Local CSeq on the Dialog Mailbox.
     *
     * @param id Dialog identity
     * @return allocated sequence number
     */
    java.util.concurrent.CompletionStage<Long> nextLocalSequence(DialogId id) {
        return requireDialog(id).nextLocalSequence();
    }

    /**
     * Validates and records one Remote CSeq on the Dialog Mailbox.
     *
     * @param id Dialog identity
     * @param sequenceNumber received sequence number
     * @return validation completion
     */
    java.util.concurrent.CompletionStage<Void> acceptRemoteSequence(DialogId id, long sequenceNumber) {
        return requireDialog(id).acceptRemoteSequence(sequenceNumber);
    }

    /** Terminates active Dialogs and releases executors owned by this manager. */
    @Override
    public void close() {
        synchronized (lifecycleMonitor) {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            List<SipDialog> dialogs = repository.dialogs();
            if (dialogs.isEmpty()) {
                activeDialogsStopped.complete(null);
            } else {
                dialogsStopping = dialogs.size();
                dialogs.forEach(dialog -> dialog.terminated().whenComplete(
                        (ignored, failure) -> dialogStopped(failure)
                ));
                dialogs.forEach(dialog -> dialog.shutdown(DialogTerminationReason.MANAGER_SHUTDOWN));
            }
        }
        try {
            activeDialogsStopped.get(CLOSE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            reportManagerFailure(exception);
        } catch (Exception exception) {
            reportManagerFailure(exception);
        }
        if (ownsExecutors) {
            shutdownExecutor(ownedCallbackExecutor);
            shutdownExecutor(ownedDialogExecutor);
        }
    }

    private SipDialog requireDialog(DialogId id) {
        return repository.find(Objects.requireNonNull(id, "id")).orElseThrow(
                () -> new IllegalArgumentException("unknown Dialog: " + id)
        );
    }

    private void dialogTerminated(SipDialog dialog) {
        repository.remove(dialog.id(), dialog);
    }

    private void dialogStopped(Throwable failure) {
        synchronized (lifecycleMonitor) {
            if (failure != null && dialogStopFailure == null) {
                dialogStopFailure = failure;
            }
            dialogsStopping--;
            if (dialogsStopping == 0) {
                if (dialogStopFailure == null) {
                    activeDialogsStopped.complete(null);
                } else {
                    activeDialogsStopped.completeExceptionally(dialogStopFailure);
                }
            }
        }
    }

    private void ensureOpen() {
        if (closed.get()) {
            throw new IllegalStateException("Dialog manager is closed");
        }
    }

    private void reportManagerFailure(Throwable cause) {
        try {
            listener.onManagerFailure(cause);
        } catch (Throwable ignored) {
            System.getLogger(getClass().getName()).log(
                    System.Logger.Level.WARNING,
                    "Dialog manager failure listener failed",
                    ignored
            );
        }
    }

    private static ExecutorService newVirtualExecutor(String prefix) {
        return Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name(prefix, 0).factory());
    }

    private static void shutdownExecutor(ExecutorService executor) {
        executor.shutdownNow();
        if (!Thread.currentThread().isVirtual()) {
            try {
                executor.awaitTermination(CLOSE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
