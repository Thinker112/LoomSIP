package org.loomsip.dialog;

import org.loomsip.concurrent.MailboxClosedException;
import org.loomsip.concurrent.SerialMailbox;
import org.loomsip.message.SipMessage;
import org.loomsip.message.SipMethod;
import org.loomsip.message.SipBody;
import org.loomsip.message.SipHeaders;
import org.loomsip.message.SipRequest;
import org.loomsip.message.SipResponse;
import org.loomsip.message.header.CSeqHeaderValue;
import org.loomsip.message.header.SipHeaderValueException;
import org.loomsip.message.header.SipHeaderValues;
import org.loomsip.transaction.TransportReliability;
import org.loomsip.transaction.invite.InviteClientHandle;
import org.loomsip.transaction.noninvite.ClientTransactionHandle;
import org.loomsip.transaction.tu.TuCallbackDispatcher;
import org.loomsip.transport.TransportContext;
import org.loomsip.transport.TransportEndpoint;

import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
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
    private static final int ACKNOWLEDGED_HISTORY_LIMIT = 64;

    private final SerialMailbox<DialogEvent> mailbox;
    private final TuCallbackDispatcher<Runnable> callbacks;
    private final DialogLifecycleListener listener;
    private final Consumer<? super SipDialog> terminationCallback;
    private final DialogRuntime runtime;
    private final DialogRequestRuntime requestRuntime;
    private final DialogTimerManager timers;
    private final CompletableFuture<Void> terminated = new CompletableFuture<>();
    private final Map<DialogInviteKey, DialogAckTransmission> uacAcks = new HashMap<>();
    private final Map<DialogInviteKey, UasSuccessExchange> uasSuccesses = new HashMap<>();
    private final Set<DialogInviteKey> acknowledgedInvites = new LinkedHashSet<>();

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
        this(
                initialSnapshot,
                dialogExecutor,
                callbackExecutor,
                config,
                listener,
                terminationCallback,
                null,
                null
        );
    }

    SipDialog(
            DialogSnapshot initialSnapshot,
            Executor dialogExecutor,
            Executor callbackExecutor,
            DialogConfig config,
            DialogLifecycleListener listener,
            Consumer<? super SipDialog> terminationCallback,
            DialogRuntime runtime,
            DialogRequestRuntime requestRuntime
    ) {
        snapshot = Objects.requireNonNull(initialSnapshot, "initialSnapshot");
        this.listener = Objects.requireNonNull(listener, "listener");
        this.terminationCallback = Objects.requireNonNull(terminationCallback, "terminationCallback");
        this.runtime = runtime;
        this.requestRuntime = requestRuntime;
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
        timers = runtime == null
                ? null
                : new DialogTimerManager(
                        runtime.scheduler(),
                        this::submitTimerEvent,
                        this::handleInfrastructureFailure
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

    @Override
    public CompletionStage<InviteClientHandle> sendReInvite(
            SipHeaders additionalHeaders,
            SipBody body
    ) {
        DialogRequestRuntime selected = requestRuntime;
        if (selected == null) {
            return CompletableFuture.failedFuture(new IllegalStateException(
                    "Dialog request runtime is not configured"
            ));
        }
        return prepareRequest(SipMethod.INVITE, additionalHeaders, body)
                .thenCompose(selected::sendInvite);
    }

    @Override
    public CompletionStage<ClientTransactionHandle> sendBye() {
        DialogRequestRuntime selected = requestRuntime;
        if (selected == null) {
            return CompletableFuture.failedFuture(new IllegalStateException(
                    "Dialog request runtime is not configured"
            ));
        }
        return prepareRequest(SipMethod.BYE, SipHeaders.empty(), SipBody.empty())
                .thenCompose(selected::sendNonInvite)
                .thenCompose(transaction -> transitionTo(
                        DialogState.TERMINATED,
                        DialogTerminationReason.LOCAL_BYE
                ).thenApply(ignored -> transaction));
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

    CompletionStage<Void> receiveInDialogRequest(SipRequest request) {
        CompletableFuture<Void> result = new CompletableFuture<>();
        submit(new DialogInDialogRequestReceived(request, result), result);
        return result.minimalCompletionStage();
    }

    private CompletionStage<DialogPreparedRequest> prepareRequest(
            SipMethod method,
            SipHeaders additionalHeaders,
            SipBody body
    ) {
        CompletableFuture<DialogPreparedRequest> result = new CompletableFuture<>();
        submit(new DialogRequestRequested(method, additionalHeaders, body, result), result);
        return result.minimalCompletionStage();
    }

    CompletionStage<DialogAckTransmission> prepareUacAck(
            SipRequest invite,
            SipResponse response
    ) {
        CompletableFuture<DialogAckTransmission> result = new CompletableFuture<>();
        submit(new DialogUacSuccessReceived(invite, response, result), result);
        return result.minimalCompletionStage();
    }

    CompletionStage<Void> releaseUacExchange(DialogInviteKey key) {
        CompletableFuture<Void> result = new CompletableFuture<>();
        submit(new DialogUacExchangeReleased(key, result), result);
        return result.minimalCompletionStage();
    }

    CompletionStage<Void> registerUasSuccess(
            SipResponse response,
            TransportEndpoint responseTarget,
            TransportReliability reliability
    ) {
        CompletableFuture<Void> result = new CompletableFuture<>();
        submit(new DialogUasSuccessRegistered(response, responseTarget, reliability, result), result);
        return result.minimalCompletionStage();
    }

    CompletionStage<Boolean> receiveAck(SipRequest ack, TransportContext context) {
        CompletableFuture<Boolean> result = new CompletableFuture<>();
        submit(new DialogAckReceived(ack, context, result), result);
        return result.minimalCompletionStage();
    }

    CompletionStage<Void> releaseUasExchange(DialogInviteKey key) {
        CompletableFuture<Void> result = new CompletableFuture<>();
        submit(new DialogUasExchangeReleased(key, result), result);
        return result.minimalCompletionStage();
    }

    void reportReliabilityFailure(
            DialogInviteKey key,
            SipMessage message,
            Throwable cause
    ) {
        submitReliabilityFailure(key, message, cause);
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
        } else if (event instanceof DialogRequestRequested requested) {
            handleRequestRequested(requested);
        } else if (event instanceof DialogInDialogRequestReceived received) {
            handleInDialogRequest(received);
        } else if (event instanceof DialogUacSuccessReceived success) {
            handleUacSuccess(success);
        } else if (event instanceof DialogUacExchangeReleased released) {
            uacAcks.remove(released.key());
            released.result().complete(null);
        } else if (event instanceof DialogUasSuccessRegistered success) {
            handleUasSuccess(success);
        } else if (event instanceof DialogUasExchangeReleased released) {
            uasSuccesses.remove(released.key());
            if (timers != null) {
                timers.cancelInvite(released.key());
            }
            released.result().complete(null);
        } else if (event instanceof DialogAckReceived ack) {
            handleAck(ack);
        } else if (event instanceof DialogTimerExpired expired) {
            handleTimer(expired);
        } else if (event instanceof DialogReliabilityTransportFailed failure) {
            notifyTu(() -> listener.onReliabilityTransportFailure(this, failure.message(), failure.cause()));
        } else if (event instanceof DialogShutdown shutdown) {
            terminate(shutdown.reason());
        }
    }

    private void handleUacSuccess(DialogUacSuccessReceived event) {
        if (!ensureActive(event.result())) {
            return;
        }
        if (runtime == null) {
            event.result().completeExceptionally(new IllegalStateException(
                    "Dialog reliability runtime is not configured"
            ));
            return;
        }
        try {
            long cseq = SipHeaderValues.cseq(event.invite().headers()).sequenceNumber();
            DialogInviteKey key = new DialogInviteKey(id(), cseq);
            DialogAckTransmission transmission = uacAcks.computeIfAbsent(key, ignored ->
                    DialogAcknowledgements.create2xxAck(
                            snapshot,
                            event.invite(),
                            event.response(),
                            runtime.nextBranch()
                    )
            );
            event.result().complete(transmission);
        } catch (Throwable cause) {
            event.result().completeExceptionally(cause);
        }
    }

    private void handleUasSuccess(DialogUasSuccessRegistered event) {
        if (!ensureActive(event.result())) {
            return;
        }
        if (runtime == null || timers == null) {
            event.result().completeExceptionally(new IllegalStateException(
                    "Dialog reliability runtime is not configured"
            ));
            return;
        }
        try {
            validateUasSuccess(event.response());
            long cseq = SipHeaderValues.cseq(event.response().headers()).sequenceNumber();
            DialogInviteKey key = new DialogInviteKey(id(), cseq);
            if (uasSuccesses.containsKey(key)) {
                event.result().complete(null);
                return;
            }
            UasSuccessExchange exchange = new UasSuccessExchange(
                    event.response(),
                    event.responseTarget(),
                    event.reliability(),
                    runtime.timerConfig().t1()
            );
            uasSuccesses.put(key, exchange);
            try {
                timers.start(
                        new DialogTimerKey(key, DialogTimer.TWO_XX_ACK_TIMEOUT),
                        runtime.timerConfig().sixtyFourT1()
                );
                if (event.reliability() == TransportReliability.UNRELIABLE) {
                    timers.start(
                            new DialogTimerKey(key, DialogTimer.TWO_XX_RETRANSMIT),
                            exchange.retransmitInterval
                    );
                }
            } catch (Throwable cause) {
                timers.cancelInvite(key);
                uasSuccesses.remove(key);
                throw cause;
            }
            event.result().complete(null);
        } catch (Throwable cause) {
            event.result().completeExceptionally(cause);
        }
    }

    private void handleAck(DialogAckReceived event) {
        if (!ensureActive(event.result())) {
            return;
        }
        try {
            if (!SipMethod.ACK.equals(event.ack().method())) {
                event.result().complete(false);
                return;
            }
            CSeqHeaderValue cseq = SipHeaderValues.cseq(event.ack().headers());
            if (!SipMethod.ACK.equals(cseq.method())
                    || !id().equals(DialogId.from(event.ack().headers(), DialogRole.UAS))) {
                event.result().complete(false);
                return;
            }
            DialogInviteKey key = new DialogInviteKey(id(), cseq.sequenceNumber());
            if (acknowledgedInvites.contains(key)) {
                event.result().complete(true);
                return;
            }
            UasSuccessExchange exchange = uasSuccesses.remove(key);
            if (exchange == null) {
                event.result().complete(false);
                return;
            }
            timers.cancelInvite(key);
            rememberAcknowledged(key);
            notifyTu(() -> listener.onAckReceived(this, event.ack(), event.context()));
            event.result().complete(true);
        } catch (SipHeaderValueException | IllegalArgumentException exception) {
            event.result().complete(false);
        }
    }

    private void handleTimer(DialogTimerExpired event) {
        if (timers == null || !timers.consumeIfCurrent(event.key(), event.generation())) {
            return;
        }
        DialogInviteKey invite = event.key().invite();
        UasSuccessExchange exchange = uasSuccesses.get(invite);
        if (exchange == null) {
            return;
        }
        switch (event.key().timer()) {
            case TWO_XX_RETRANSMIT -> {
                sendReliabilityMessage(invite, exchange.response, exchange.responseTarget);
                exchange.retransmitInterval = minimum(
                        exchange.retransmitInterval.multipliedBy(2),
                        runtime.timerConfig().t2()
                );
                timers.start(event.key(), exchange.retransmitInterval);
            }
            case TWO_XX_ACK_TIMEOUT -> {
                uasSuccesses.remove(invite);
                timers.cancelInvite(invite);
                notifyTu(() -> listener.onAckTimeout(this, invite.inviteCSeq()));
            }
        }
    }

    private void sendReliabilityMessage(
            DialogInviteKey key,
            SipMessage message,
            TransportEndpoint target
    ) {
        final CompletionStage<?> sendStage;
        try {
            sendStage = runtime.send(message, target);
        } catch (Throwable cause) {
            submitReliabilityFailure(key, message, cause);
            return;
        }
        sendStage.whenComplete((ignored, failure) -> {
            if (failure != null) {
                submitReliabilityFailure(key, message, unwrapCompletionFailure(failure));
            }
        });
    }

    private void validateUasSuccess(SipResponse response) throws SipHeaderValueException {
        if (snapshot.role() != DialogRole.UAS || snapshot.state() != DialogState.CONFIRMED
                || response.statusCode() < 200 || response.statusCode() >= 300
                || !id().equals(DialogId.from(response.headers(), DialogRole.UAS))) {
            throw new IllegalArgumentException("UAS 2xx does not match a confirmed Dialog");
        }
        CSeqHeaderValue cseq = SipHeaderValues.cseq(response.headers());
        if (!SipMethod.INVITE.equals(cseq.method())) {
            throw new IllegalArgumentException("UAS reliability requires an INVITE 2xx");
        }
    }

    private void rememberAcknowledged(DialogInviteKey key) {
        acknowledgedInvites.add(key);
        if (acknowledgedInvites.size() > ACKNOWLEDGED_HISTORY_LIMIT) {
            DialogInviteKey oldest = acknowledgedInvites.iterator().next();
            acknowledgedInvites.remove(oldest);
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

    private void handleRequestRequested(DialogRequestRequested event) {
        if (!ensureActive(event.result())) {
            return;
        }
        if (requestRuntime == null || runtime == null) {
            event.result().completeExceptionally(new IllegalStateException(
                    "Dialog request runtime is not configured"
            ));
            return;
        }
        if (snapshot.state() != DialogState.CONFIRMED) {
            event.result().completeExceptionally(new IllegalStateException(
                    "in-Dialog requests require a confirmed Dialog"
            ));
            return;
        }
        if (snapshot.localCSeq() == CSeqHeaderValue.MAX_SEQUENCE_NUMBER) {
            event.result().completeExceptionally(new IllegalStateException("Local CSeq is exhausted"));
            return;
        }
        try {
            long next = Math.incrementExact(snapshot.localCSeq());
            DialogPreparedRequest prepared = DialogRequests.create(
                    snapshot,
                    new DialogRoutePlanner().plan(snapshot),
                    requestRuntime.profile(),
                    event.method(),
                    next,
                    event.additionalHeaders(),
                    event.body(),
                    runtime.nextBranch()
            );
            updateSnapshot(snapshot.state(), next, snapshot.remoteCSeq(), snapshot.remoteTarget());
            event.result().complete(prepared);
        } catch (Throwable cause) {
            event.result().completeExceptionally(cause);
        }
    }

    private void handleInDialogRequest(DialogInDialogRequestReceived event) {
        if (snapshot.state() != DialogState.CONFIRMED) {
            event.result().completeExceptionally(new DialogRequestRejectedException(
                    481,
                    "Call/Transaction Does Not Exist",
                    "in-Dialog request requires a confirmed active Dialog"
            ));
            return;
        }
        try {
            SipRequest request = event.request();
            if (!SipMethod.INVITE.equals(request.method()) && !SipMethod.BYE.equals(request.method())) {
                throw new DialogRequestRejectedException(
                        405,
                        "Method Not Allowed",
                        "4D supports only inbound re-INVITE and BYE"
                );
            }
            if (!id().equals(DialogId.from(request.headers(), DialogRole.UAS))) {
                throw new DialogRequestRejectedException(
                        481,
                        "Call/Transaction Does Not Exist",
                        "request does not match the local Dialog identity"
                );
            }
            CSeqHeaderValue cseq = SipHeaderValues.cseq(request.headers());
            if (!request.method().equals(cseq.method()) || cseq.sequenceNumber() <= snapshot.remoteCSeq()) {
                throw new DialogRequestRejectedException(
                        500,
                        "Server Internal Error",
                        "remote CSeq must match the method and increase monotonically"
                );
            }
            Optional<org.loomsip.message.SipUri> remoteTarget = snapshot.remoteTarget();
            if (SipMethod.INVITE.equals(request.method())) {
                java.util.List<org.loomsip.message.header.ContactHeaderValue> contacts =
                        org.loomsip.message.header.DialogHeaderValues.contacts(request.headers());
                if (contacts.size() != 1 || contacts.getFirst().isWildcard()) {
                    throw new DialogRequestRejectedException(
                            400,
                            "Bad Request",
                            "target-refresh request requires one non-wildcard Contact"
                    );
                }
                remoteTarget = Optional.of(contacts.getFirst().address().orElseThrow().uri());
            }
            updateSnapshot(snapshot.state(), snapshot.localCSeq(), cseq.sequenceNumber(), remoteTarget);
            if (SipMethod.BYE.equals(request.method())) {
                terminate(DialogTerminationReason.REMOTE_BYE);
            }
            event.result().complete(null);
        } catch (DialogRequestRejectedException cause) {
            event.result().completeExceptionally(cause);
        } catch (SipHeaderValueException | IllegalArgumentException cause) {
            event.result().completeExceptionally(new DialogRequestRejectedException(
                    400,
                    "Bad Request",
                    cause.getMessage()
            ));
        }
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
        if (timers != null) {
            timers.close();
        }
        uacAcks.clear();
        uasSuccesses.clear();
        acknowledgedInvites.clear();
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

    private void submitTimerEvent(DialogTimerExpired event) {
        try {
            mailbox.submit(event);
        } catch (MailboxClosedException ignored) {
            // Dialog termination already cancelled all current timers.
        } catch (Throwable cause) {
            handleInfrastructureFailure(cause);
        }
    }

    private void submitReliabilityFailure(
            DialogInviteKey key,
            SipMessage message,
            Throwable cause
    ) {
        try {
            mailbox.submit(new DialogReliabilityTransportFailed(key, message, cause));
        } catch (MailboxClosedException ignored) {
            // A late transport completion cannot revive a terminated Dialog.
        } catch (Throwable infrastructureFailure) {
            handleInfrastructureFailure(infrastructureFailure);
        }
    }

    private static Throwable unwrapCompletionFailure(Throwable failure) {
        return failure instanceof java.util.concurrent.CompletionException && failure.getCause() != null
                ? failure.getCause()
                : failure;
    }

    private static Duration minimum(Duration first, Duration second) {
        return first.compareTo(second) <= 0 ? first : second;
    }

    private static boolean isValidTransition(DialogState current, DialogState target) {
        return switch (current) {
            case EARLY -> target == DialogState.CONFIRMED || target == DialogState.TERMINATED;
            case CONFIRMED -> target == DialogState.TERMINATED;
            case TERMINATED -> false;
        };
    }

    private static final class UasSuccessExchange {

        private final SipResponse response;
        private final TransportEndpoint responseTarget;
        private final TransportReliability reliability;
        private Duration retransmitInterval;

        private UasSuccessExchange(
                SipResponse response,
                TransportEndpoint responseTarget,
                TransportReliability reliability,
                Duration retransmitInterval
        ) {
            this.response = response;
            this.responseTarget = responseTarget;
            this.reliability = reliability;
            this.retransmitInterval = retransmitInterval;
        }
    }
}
