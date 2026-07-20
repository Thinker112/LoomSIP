package org.loomsip.dialog;

import org.loomsip.message.SipUri;
import org.loomsip.message.SipMessage;
import org.loomsip.message.SipRequest;
import org.loomsip.message.SipResponse;
import org.loomsip.transaction.TransportReliability;
import org.loomsip.transport.SendResult;
import org.loomsip.transport.TransportContext;
import org.loomsip.transport.TransportEndpoint;
import org.loomsip.transport.TransportProtocol;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
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
    private final DialogRuntime runtime;
    private final DialogRequestRuntime requestRuntime;
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
                true,
                null,
                null
        );
    }

    /**
     * Creates a manager with Dialog reliability services and owned virtual-thread executors.
     *
     * @param config Dialog capacities
     * @param listener ordered lifecycle listener
     * @param runtime externally owned protocol runtime
     */
    public DialogManager(
            DialogConfig config,
            DialogLifecycleListener listener,
            DialogRuntime runtime
    ) {
        this(
                config,
                listener,
                new InMemoryDialogRepository(config.dialogs()),
                newVirtualExecutor("loomsip-dialog-"),
                newVirtualExecutor("loomsip-dialog-tu-"),
                true,
                Objects.requireNonNull(runtime, "runtime"),
                null
        );
    }

    /**
     * Creates a manager with reliability and in-Dialog request services.
     *
     * @param config Dialog capacities
     * @param listener ordered lifecycle listener
     * @param requestRuntime externally owned request runtime
     */
    public DialogManager(
            DialogConfig config,
            DialogLifecycleListener listener,
            DialogRequestRuntime requestRuntime
    ) {
        this(
                config,
                listener,
                new InMemoryDialogRepository(config.dialogs()),
                newVirtualExecutor("loomsip-dialog-"),
                newVirtualExecutor("loomsip-dialog-tu-"),
                true,
                Objects.requireNonNull(requestRuntime, "requestRuntime").dialogRuntime(),
                requestRuntime
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
        this(config, listener, repository, dialogExecutor, callbackExecutor, false, null, null);
    }

    /**
     * Creates a manager with external repository, executors, and protocol runtime.
     *
     * @param config Dialog capacities
     * @param listener ordered lifecycle listener
     * @param repository externally owned Dialog repository
     * @param dialogExecutor state executor
     * @param callbackExecutor TU callback executor
     * @param runtime externally owned protocol runtime
     */
    public DialogManager(
            DialogConfig config,
            DialogLifecycleListener listener,
            DialogRepository repository,
            Executor dialogExecutor,
            Executor callbackExecutor,
            DialogRuntime runtime
    ) {
        this(
                config,
                listener,
                repository,
                dialogExecutor,
                callbackExecutor,
                false,
                Objects.requireNonNull(runtime, "runtime"),
                null
        );
    }

    /**
     * Creates a manager with external repository, executors, and request runtime.
     *
     * @param config Dialog capacities
     * @param listener ordered lifecycle listener
     * @param repository externally owned Dialog repository
     * @param dialogExecutor state executor
     * @param callbackExecutor TU callback executor
     * @param requestRuntime externally owned request runtime
     */
    public DialogManager(
            DialogConfig config,
            DialogLifecycleListener listener,
            DialogRepository repository,
            Executor dialogExecutor,
            Executor callbackExecutor,
            DialogRequestRuntime requestRuntime
    ) {
        this(
                config,
                listener,
                repository,
                dialogExecutor,
                callbackExecutor,
                false,
                Objects.requireNonNull(requestRuntime, "requestRuntime").dialogRuntime(),
                requestRuntime
        );
    }

    private DialogManager(
            DialogConfig config,
            DialogLifecycleListener listener,
            DialogRepository repository,
            Executor dialogExecutor,
            Executor callbackExecutor,
            boolean ownsExecutors,
            DialogRuntime runtime,
            DialogRequestRuntime requestRuntime
    ) {
        this.config = Objects.requireNonNull(config, "config");
        this.listener = Objects.requireNonNull(listener, "listener");
        this.repository = Objects.requireNonNull(repository, "repository");
        this.runtime = runtime;
        this.requestRuntime = requestRuntime;
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
                    this::dialogTerminated,
                    runtime,
                    requestRuntime
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

    CompletionStage<DialogSessionState> configureSessionTimer(
            DialogId id,
            SessionTimerNegotiator.NegotiatedSessionTimer negotiated,
            boolean localRefresher
    ) {
        return requireDialog(id).configureSessionTimer(negotiated, localRefresher);
    }

    CompletionStage<Boolean> retrySessionRefresh(
            DialogId id,
            long sequenceNumber,
            int minimumSeconds
    ) {
        return requireDialog(id).retrySessionRefresh(sequenceNumber, minimumSeconds);
    }

    void failSessionRefresh(DialogId id, long sequenceNumber, Throwable cause) {
        repository.find(id).ifPresent(dialog -> dialog.failSessionRefresh(sequenceNumber, cause));
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

    CompletionStage<Void> receiveInDialogRequest(DialogId id, SipRequest request) {
        Optional<DialogHandle> selected = find(id);
        if (selected.isEmpty()) {
            return CompletableFuture.failedFuture(new DialogRequestRejectedException(
                    481,
                    "Call/Transaction Does Not Exist",
                    "unknown Dialog: " + id
            ));
        }
        return ((SipDialog) selected.orElseThrow()).receiveInDialogRequest(request);
    }

    CompletionStage<DialogAckTransmission> prepareUacAck(
            DialogId id,
            SipRequest invite,
            SipResponse response
    ) {
        requireRuntime();
        return requireDialog(id).prepareUacAck(invite, response);
    }

    CompletionStage<SendResult> sendUacAck(
            DialogAckTransmission transmission,
            TransportProtocol preferredTransport
    ) {
        DialogRuntime selected = requireRuntime();
        return selected.targetResolver()
                .resolve(transmission.nextHop(), preferredTransport)
                .thenCompose(target -> selected.send(transmission.ack(), target));
    }

    CompletionStage<Void> releaseUacExchange(DialogInviteKey key) {
        if (runtime == null) {
            return CompletableFuture.completedFuture(null);
        }
        Optional<DialogHandle> selected = find(key.dialogId());
        return selected.isEmpty()
                ? CompletableFuture.completedFuture(null)
                : ((SipDialog) selected.orElseThrow()).releaseUacExchange(key);
    }

    CompletionStage<Void> registerUasSuccess(
            DialogId id,
            SipResponse response,
            TransportEndpoint responseTarget,
            TransportReliability reliability
    ) {
        requireRuntime();
        return requireDialog(id).registerUasSuccess(response, responseTarget, reliability);
    }

    CompletionStage<Boolean> receiveAck(
            DialogId id,
            SipRequest ack,
            TransportContext context
    ) {
        if (runtime == null) {
            return CompletableFuture.completedFuture(false);
        }
        Optional<DialogHandle> selected = find(id);
        return selected.isEmpty()
                ? CompletableFuture.completedFuture(false)
                : ((SipDialog) selected.orElseThrow()).receiveAck(ack, context);
    }

    CompletionStage<Void> releaseUasExchange(DialogInviteKey key) {
        if (runtime == null) {
            return CompletableFuture.completedFuture(null);
        }
        Optional<DialogHandle> selected = find(key.dialogId());
        return selected.isEmpty()
                ? CompletableFuture.completedFuture(null)
                : ((SipDialog) selected.orElseThrow()).releaseUasExchange(key);
    }

    void reportReliabilityFailure(
            DialogInviteKey key,
            SipMessage message,
            Throwable cause
    ) {
        find(key.dialogId()).ifPresent(dialog ->
                ((SipDialog) dialog).reportReliabilityFailure(key, message, cause)
        );
    }

    boolean reliabilityEnabled() {
        return runtime != null;
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

    private DialogRuntime requireRuntime() {
        if (runtime == null) {
            throw new IllegalStateException("Dialog reliability runtime is not configured");
        }
        return runtime;
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
