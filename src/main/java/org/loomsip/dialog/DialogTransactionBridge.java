package org.loomsip.dialog;

import org.loomsip.info.InfoDispatcher;
import org.loomsip.info.InfoHandler;
import org.loomsip.info.InfoRequest;
import org.loomsip.info.InfoResponse;
import org.loomsip.message.SipHeaders;
import org.loomsip.message.SipBody;
import org.loomsip.message.SipMethod;
import org.loomsip.message.SipRequest;
import org.loomsip.message.SipResponse;
import org.loomsip.message.SipResponses;
import org.loomsip.message.SipUri;
import org.loomsip.message.header.CSeqHeaderValue;
import org.loomsip.message.header.ContactHeaderValue;
import org.loomsip.message.header.DialogHeaderValues;
import org.loomsip.message.header.InfoPackageHeaderValue;
import org.loomsip.message.header.RecordRouteHeaderValue;
import org.loomsip.message.header.RecvInfoHeaderValue;
import org.loomsip.message.header.SipHeaderValueException;
import org.loomsip.message.header.SipHeaderValues;
import org.loomsip.message.header.SipExtensionSupport;
import org.loomsip.message.header.SessionExpiresHeaderValue;
import org.loomsip.message.header.SessionRefresher;
import org.loomsip.transaction.TransportReliability;
import org.loomsip.transaction.invite.InviteClientHandle;
import org.loomsip.transaction.invite.InviteClientListener;
import org.loomsip.transaction.invite.InviteServerHandle;
import org.loomsip.transaction.invite.InviteServerListener;
import org.loomsip.transaction.noninvite.ClientTransactionHandle;
import org.loomsip.transaction.noninvite.NonInviteClientListener;
import org.loomsip.transaction.noninvite.NonInviteServerListener;
import org.loomsip.transaction.noninvite.ServerTransactionHandle;
import org.loomsip.transaction.timer.SipTimer;
import org.loomsip.transport.TransportContext;
import org.loomsip.transport.TransportEndpoint;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Ordered bridge from INVITE and Non-INVITE transaction callbacks to Dialog state.
 *
 * <p>The bridge exposes separate client and server listener adapters. The
 * application listeners remain outside Dialog Mailboxes, while every state
 * mutation is submitted through {@link DialogManager}.</p>
 *
 * <pre>{@code
 * ICT callback --> client adapter --> DialogManager / Mailbox
 *                                          |
 *                                          v
 *                              application client listener
 *
 * application server thread(s)
 *              |
 *              v
 * wrapped IST handle / response monitor
 *              |
 *              +--> DialogManager / Mailbox -- update completed --+
 *                                                               |
 *                                                               v
 *                                                     IST Mailbox / Transport
 *
 * NICT/ICT final response, timeout, or transport failure
 *                         |
 *                         v
 *              Session refresh correlation
 *               (Dialog ID + CSeq + generation)
 *                         |
 *                         v
 *                  Dialog Mailbox
 *             retry / replace timer / terminate
 * }</pre>
 */
public final class DialogTransactionBridge {

    private final DialogManager dialogs;
    private final InviteClientListener clientDelegate;
    private final InviteServerListener serverDelegate;
    private final NonInviteClientListener nonInviteClientDelegate;
    private final NonInviteServerListener nonInviteServerDelegate;
    private final ReliableProvisionalManager reliableProvisionals;
    private final InfoDispatcher infoDispatcher;
    private final DialogRoutePlanner routePlanner = new DialogRoutePlanner();
    private final ClientSide clientSide = new ClientSide();
    private final ServerSide serverSide = new ServerSide();
    private final NonInviteClientSide nonInviteClientSide = new NonInviteClientSide();
    private final NonInviteServerSide nonInviteServerSide = new NonInviteServerSide();
    private final ConcurrentHashMap<InviteServerHandle, BridgedServerHandle> serverHandles =
            new ConcurrentHashMap<>();

    /**
     * Creates a bridge around application-level INVITE listeners.
     *
     * @param dialogs Dialog lifecycle owner
     * @param clientDelegate application client listener
     * @param serverDelegate application server listener
     */
    public DialogTransactionBridge(
            DialogManager dialogs,
            InviteClientListener clientDelegate,
            InviteServerListener serverDelegate
    ) {
        this(
                dialogs,
                clientDelegate,
                serverDelegate,
                (transaction, response, context) -> {
                },
                (transaction, request, context) -> {
                },
                null
        );
    }

    /**
     * Creates a bridge for INVITE and Non-INVITE application listeners.
     *
     * @param dialogs Dialog lifecycle owner
     * @param clientDelegate application INVITE client listener
     * @param serverDelegate application INVITE server listener
     * @param nonInviteClientDelegate application Non-INVITE client listener
     * @param nonInviteServerDelegate application Non-INVITE server listener
     */
    public DialogTransactionBridge(
            DialogManager dialogs,
            InviteClientListener clientDelegate,
            InviteServerListener serverDelegate,
            NonInviteClientListener nonInviteClientDelegate,
            NonInviteServerListener nonInviteServerDelegate
    ) {
        this(
                dialogs,
                clientDelegate,
                serverDelegate,
                nonInviteClientDelegate,
                nonInviteServerDelegate,
                null
        );
    }

    /**
     * Creates a bridge with optional RFC 3262 reliable provisional coordination.
     *
     * <p>The supplied manager is shared by client and server bridge adapters.
     * The stack remains responsible for closing it after Dialog and transaction
     * callbacks are stopped.</p>
     *
     * @param dialogs Dialog lifecycle owner
     * @param clientDelegate application INVITE client listener
     * @param serverDelegate application INVITE server listener
     * @param nonInviteClientDelegate application Non-INVITE client listener
     * @param nonInviteServerDelegate application Non-INVITE server listener
     * @param reliableProvisionals optional RFC 3262 manager, or {@code null} to disable 100rel
     */
    public DialogTransactionBridge(
            DialogManager dialogs,
            InviteClientListener clientDelegate,
            InviteServerListener serverDelegate,
            NonInviteClientListener nonInviteClientDelegate,
            NonInviteServerListener nonInviteServerDelegate,
            ReliableProvisionalManager reliableProvisionals
    ) {
        this(
                dialogs,
                clientDelegate,
                serverDelegate,
                nonInviteClientDelegate,
                nonInviteServerDelegate,
                reliableProvisionals,
                null
        );
    }

    /**
     * Creates a bridge with optional reliable provisional and INFO package coordination.
     *
     * @param dialogs Dialog lifecycle owner
     * @param clientDelegate application INVITE client listener
     * @param serverDelegate application INVITE server listener
     * @param nonInviteClientDelegate application Non-INVITE client listener
     * @param nonInviteServerDelegate fallback application Non-INVITE server listener
     * @param reliableProvisionals optional RFC 3262 manager, or {@code null} to disable 100rel
     * @param infoDispatcher optional RFC 6086 package dispatcher, or {@code null} to reject packaged INFO
     */
    public DialogTransactionBridge(
            DialogManager dialogs,
            InviteClientListener clientDelegate,
            InviteServerListener serverDelegate,
            NonInviteClientListener nonInviteClientDelegate,
            NonInviteServerListener nonInviteServerDelegate,
            ReliableProvisionalManager reliableProvisionals,
            InfoDispatcher infoDispatcher
    ) {
        this.dialogs = Objects.requireNonNull(dialogs, "dialogs");
        this.clientDelegate = Objects.requireNonNull(clientDelegate, "clientDelegate");
        this.serverDelegate = Objects.requireNonNull(serverDelegate, "serverDelegate");
        this.nonInviteClientDelegate = Objects.requireNonNull(
                nonInviteClientDelegate,
                "nonInviteClientDelegate"
        );
        this.nonInviteServerDelegate = Objects.requireNonNull(
                nonInviteServerDelegate,
                "nonInviteServerDelegate"
        );
        this.reliableProvisionals = reliableProvisionals;
        this.infoDispatcher = infoDispatcher;
    }

    /**
     * Returns the listener installed on the INVITE client transaction manager.
     *
     * @return Dialog-aware client listener
     */
    public InviteClientListener clientListener() {
        return clientSide;
    }

    /**
     * Returns the listener installed on the INVITE server transaction manager.
     *
     * @return Dialog-aware server listener
     */
    public InviteServerListener serverListener() {
        return serverSide;
    }

    /**
     * Returns the listener installed on the Non-INVITE client transaction manager.
     *
     * @return Dialog-aware Non-INVITE client listener
     */
    public NonInviteClientListener nonInviteClientListener() {
        return nonInviteClientSide;
    }

    /**
     * Returns the listener installed on the Non-INVITE server transaction manager.
     *
     * @return Dialog-aware Non-INVITE server listener
     */
    public NonInviteServerListener nonInviteServerListener() {
        return nonInviteServerSide;
    }

    private Optional<DialogHandle> processClientResponse(
            SipRequest invite,
            SipResponse response
    ) throws SipHeaderValueException {
        validateResponseCorrelation(invite, response);
        int status = response.statusCode();
        if (status == 100 || status < 200 && SipHeaderValues.toTag(response.headers()).isEmpty()) {
            return Optional.empty();
        }
        if (status >= 300) {
            cleanupClientEarlyDialogs(invite, DialogTerminationReason.NON_SUCCESS_FINAL_RESPONSE);
            return Optional.empty();
        }
        if (status >= 200 || status > 100) {
            DialogId id = DialogId.from(response.headers(), DialogRole.UAC);
            Optional<SipUri> remoteTarget = contactUri(
                    response.headers(),
                    status >= 200,
                    "dialog-forming INVITE response"
            );
            DialogState targetState = status < 200 ? DialogState.EARLY : DialogState.CONFIRMED;
            return Optional.of(createOrUpdateDialog(
                    uacSnapshot(invite, response, id, targetState, remoteTarget),
                    remoteTarget,
                    targetState
            ));
        }
        return Optional.empty();
    }

    private Optional<DialogHandle> processServerResponse(
            BridgedServerHandle transaction,
            SipResponse response
    ) throws SipHeaderValueException {
        SipRequest invite = transaction.invite;
        validateResponseCorrelation(invite, response);
        int status = response.statusCode();
        Optional<String> toTag = SipHeaderValues.toTag(response.headers());
        if (status == 100 || status < 200 && toTag.isEmpty()) {
            return Optional.empty();
        }
        if (status >= 300) {
            if (toTag.isPresent()) {
                DialogId id = DialogId.from(response.headers(), DialogRole.UAS);
                transaction.dialogIds.add(id);
                cleanupDialog(id, DialogTerminationReason.NON_SUCCESS_FINAL_RESPONSE);
            }
            return Optional.empty();
        }
        if (status >= 200 || status > 100) {
            DialogId id = DialogId.from(response.headers(), DialogRole.UAS);
            if (status >= 200) {
                contactUri(
                        response.headers(),
                        true,
                        "dialog-forming INVITE response"
                );
            }
            Optional<SipUri> remoteTarget = contactUri(
                    invite.headers(),
                    status >= 200,
                    "dialog-forming INVITE request"
            );
            DialogState targetState = status < 200 ? DialogState.EARLY : DialogState.CONFIRMED;
            DialogHandle dialog = createOrUpdateDialog(
                    uasSnapshot(invite, response, id, targetState, remoteTarget),
                    remoteTarget,
                    targetState
            );
            transaction.dialogIds.add(id);
            if (status >= 200 && dialogs.reliabilityEnabled()) {
                await(dialogs.registerUasSuccess(
                        id,
                        response,
                        transaction.responseTarget,
                        transaction.reliability
                ));
            }
            return Optional.of(dialog);
        }
        return Optional.empty();
    }

    private DialogHandle createOrUpdateDialog(
            DialogSnapshot candidate,
            Optional<SipUri> remoteTarget,
            DialogState targetState
    ) {
        Optional<DialogHandle> existing = dialogs.find(candidate.id());
        DialogHandle dialog = existing.orElseGet(() -> dialogs.create(candidate));
        if (existing.isPresent() && dialog.snapshot().state() == DialogState.CONFIRMED) {
            if (targetState == DialogState.CONFIRMED) {
                remoteTarget.ifPresent(target -> await(dialogs.updateRemoteTarget(candidate.id(), target)));
            }
            return dialog;
        }
        if (existing.isEmpty()) {
            return dialog;
        }
        remoteTarget.ifPresent(target -> await(dialogs.updateRemoteTarget(candidate.id(), target)));
        if (targetState == DialogState.CONFIRMED) {
            await(dialogs.transition(
                    candidate.id(),
                    DialogState.CONFIRMED,
                    DialogTerminationReason.EXPLICIT
            ));
        }
        return dialog;
    }

    private void configureSessionTimer(DialogHandle dialog, SipResponse response)
            throws SipHeaderValueException {
        if (response.statusCode() < 200
                || response.statusCode() >= 300
                || !response.headers().contains("Session-Expires")) {
            return;
        }
        SessionExpiresHeaderValue expires = SipHeaderValues.sessionExpires(response.headers());
        SessionTimerNegotiator.NegotiatedSessionTimer negotiated =
                new SessionTimerNegotiator().negotiate(expires, SessionTimerPolicy.DEFAULT);
        SessionRefresher refresher = negotiated.refresher();
        boolean localRefresher = dialog.snapshot().role() == DialogRole.UAC
                ? refresher == SessionRefresher.UAC
                : refresher == SessionRefresher.UAS;
        await(dialogs.configureSessionTimer(dialog.id(), negotiated, localRefresher));
    }

    private void handleSessionRefreshFinal(SipRequest request, SipResponse response)
            throws SipHeaderValueException {
        if (!isSessionTimerRequest(request) || response.statusCode() < 200) {
            return;
        }
        DialogId id = DialogId.from(response.headers(), DialogRole.UAC);
        long sequenceNumber = SipHeaderValues.cseq(request.headers()).sequenceNumber();
        if (response.statusCode() == 422 && response.headers().contains("Min-SE")) {
            boolean retried = awaitValue(dialogs.retrySessionRefresh(
                    id,
                    sequenceNumber,
                    SipHeaderValues.minSe(response.headers()).intervalSeconds()
            ));
            if (!retried) {
                dialogs.failSessionRefresh(
                        id,
                        sequenceNumber,
                        new IllegalStateException("Session Timer 422 retry limit reached")
                );
            }
            return;
        }
        if (response.statusCode() >= 300 || !response.headers().contains("Session-Expires")) {
            dialogs.failSessionRefresh(
                    id,
                    sequenceNumber,
                    new IllegalStateException("Session refresh failed with SIP " + response.statusCode())
            );
        }
    }

    private void failSessionRefresh(SipRequest request, Throwable cause) {
        if (!isSessionTimerRequest(request)) {
            return;
        }
        try {
            DialogId id = DialogId.from(request.headers(), DialogRole.UAC);
            long sequenceNumber = SipHeaderValues.cseq(request.headers()).sequenceNumber();
            dialogs.failSessionRefresh(id, sequenceNumber, cause);
        } catch (Throwable bridgeFailure) {
            cause.addSuppressed(bridgeFailure);
        }
    }

    private static boolean isSessionTimerRequest(SipRequest request) {
        if (!request.headers().contains("Session-Expires")
                || (request.method() != SipMethod.INVITE && request.method() != SipMethod.UPDATE)) {
            return false;
        }
        try {
            return SipHeaderValues.toTag(request.headers()).isPresent();
        } catch (SipHeaderValueException ignored) {
            return false;
        }
    }

    private DialogSnapshot uacSnapshot(
            SipRequest invite,
            SipResponse response,
            DialogId id,
            DialogState state,
            Optional<SipUri> remoteTarget
    ) throws SipHeaderValueException {
        CSeqHeaderValue cseq = SipHeaderValues.cseq(invite.headers());
        List<RecordRouteHeaderValue> recordRoutes = DialogHeaderValues.recordRoutes(response.headers());
        return new DialogSnapshot(
                id,
                DialogRole.UAC,
                state,
                DialogHeaderValues.fromAddress(invite.headers()).uri(),
                DialogHeaderValues.toAddress(response.headers()).uri(),
                cseq.sequenceNumber(),
                0,
                routePlanner.establishRouteSet(DialogRole.UAC, recordRoutes),
                remoteTarget,
                isSecure(invite)
        );
    }

    private DialogSnapshot uasSnapshot(
            SipRequest invite,
            SipResponse response,
            DialogId id,
            DialogState state,
            Optional<SipUri> remoteTarget
    ) throws SipHeaderValueException {
        CSeqHeaderValue cseq = SipHeaderValues.cseq(invite.headers());
        List<RecordRouteHeaderValue> recordRoutes = DialogHeaderValues.recordRoutes(invite.headers());
        return new DialogSnapshot(
                id,
                DialogRole.UAS,
                state,
                DialogHeaderValues.toAddress(response.headers()).uri(),
                DialogHeaderValues.fromAddress(invite.headers()).uri(),
                0,
                cseq.sequenceNumber(),
                routePlanner.establishRouteSet(DialogRole.UAS, recordRoutes),
                remoteTarget,
                isSecure(invite)
        );
    }

    private void cleanupClientEarlyDialogs(
            SipRequest invite,
            DialogTerminationReason reason
    ) throws SipHeaderValueException {
        String callId = SipHeaderValues.callId(invite.headers());
        String localTag = SipHeaderValues.fromTag(invite.headers()).orElseThrow(
                () -> new SipHeaderValueException("From tag is required for a UAC Dialog Set")
        );
        cleanupDialogSet(new DialogSetId(callId, localTag), reason);
    }

    private void releaseClientAckCache(SipRequest invite) throws SipHeaderValueException {
        if (!dialogs.reliabilityEnabled()) {
            return;
        }
        String callId = SipHeaderValues.callId(invite.headers());
        String localTag = SipHeaderValues.fromTag(invite.headers()).orElseThrow(
                () -> new SipHeaderValueException("From tag is required for a UAC Dialog Set")
        );
        long cseq = SipHeaderValues.cseq(invite.headers()).sequenceNumber();
        for (DialogHandle dialog : dialogs.findBySet(new DialogSetId(callId, localTag))) {
            await(dialogs.releaseUacExchange(new DialogInviteKey(dialog.id(), cseq)));
        }
    }

    private void cleanupDialogSet(DialogSetId setId, DialogTerminationReason reason) {
        dialogs.findBySet(setId).forEach(dialog -> {
            if (dialog.snapshot().state() == DialogState.EARLY) {
                cleanupDialog(dialog.id(), reason);
            }
        });
    }

    private void cleanupDialog(DialogId id, DialogTerminationReason reason) {
        Optional<DialogHandle> selected = dialogs.find(id);
        if (selected.isPresent() && selected.get().snapshot().state() == DialogState.EARLY) {
            await(dialogs.transition(id, DialogState.TERMINATED, reason));
        }
    }

    private static Optional<SipUri> contactUri(
            SipHeaders headers,
            boolean required,
            String source
    ) throws SipHeaderValueException {
        List<ContactHeaderValue> contacts = DialogHeaderValues.contacts(headers);
        if (contacts.isEmpty()) {
            if (required) {
                throw new SipHeaderValueException(source + " requires one Contact");
            }
            return Optional.empty();
        }
        if (contacts.size() != 1 || contacts.getFirst().isWildcard()) {
            throw new SipHeaderValueException(source + " must contain one non-wildcard Contact");
        }
        return Optional.of(contacts.getFirst().address().orElseThrow().uri());
    }

    private static void validateResponseCorrelation(SipRequest invite, SipResponse response)
            throws SipHeaderValueException {
        CSeqHeaderValue inviteCSeq = SipHeaderValues.cseq(invite.headers());
        CSeqHeaderValue responseCSeq = SipHeaderValues.cseq(response.headers());
        if (!inviteCSeq.equals(responseCSeq)
                || !SipHeaderValues.callId(invite.headers()).equals(
                        SipHeaderValues.callId(response.headers())
                )) {
            throw new SipHeaderValueException("response does not match INVITE CSeq and Call-ID");
        }
    }

    private static boolean isSecure(SipRequest invite) {
        return invite.requestUri().scheme().filter("sips"::equals).isPresent();
    }

    private static void await(CompletionStage<?> stage) {
        awaitValue(stage);
    }

    private static <T> T awaitValue(CompletionStage<T> stage) {
        try {
            return stage.toCompletableFuture().join();
        } catch (CompletionException exception) {
            if (exception.getCause() instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw exception;
        }
    }

    private void reportClientFailure(String message, Throwable cause) {
        try {
            clientDelegate.onLayerError(new DialogBridgeException(message, cause));
        } catch (Throwable listenerFailure) {
            logFailure("client Dialog bridge error listener failed", listenerFailure);
        }
    }

    private void reportServerFailure(String message, Throwable cause) {
        try {
            serverDelegate.onLayerError(new DialogBridgeException(message, cause));
        } catch (Throwable listenerFailure) {
            logFailure("server Dialog bridge error listener failed", listenerFailure);
        }
    }

    private static void logFailure(String message, Throwable cause) {
        System.getLogger(DialogTransactionBridge.class.getName()).log(
                System.Logger.Level.WARNING,
                message,
                cause
        );
    }

    private void rejectInDialogRequest(
            InviteServerHandle transaction,
            SipRequest request,
            Throwable cause
    ) {
        try {
            transaction.sendResponse(rejectionResponse(request, cause));
        } catch (Throwable responseFailure) {
            reportServerFailure("failed to send in-Dialog request rejection", responseFailure);
        }
    }

    private void rejectInDialogRequest(
            ServerTransactionHandle transaction,
            SipRequest request,
            Throwable cause
    ) {
        try {
            transaction.sendResponse(rejectionResponse(request, cause));
        } catch (Throwable responseFailure) {
            reportServerFailure("failed to send in-Dialog request rejection", responseFailure);
        }
    }

    private static SipResponse rejectionResponse(SipRequest request, Throwable cause)
            throws SipHeaderValueException {
        Throwable selected = cause instanceof CompletionException && cause.getCause() != null
                ? cause.getCause()
                : cause;
        int status = selected instanceof DialogRequestRejectedException rejected
                ? rejected.statusCode()
                : 500;
        String reason = selected instanceof DialogRequestRejectedException rejected
                ? rejected.reasonPhrase()
                : "Server Internal Error";
        String localTag = SipHeaderValues.toTag(request.headers()).orElse("loomsip");
        SipResponse response = SipResponses.createResponse(request, status, reason, localTag);
        if (status == 500) {
            return new SipResponse(
                    response.version(),
                    response.statusCode(),
                    response.reasonPhrase(),
                    response.headers().toBuilder().add("Retry-After", "0").build(),
                    response.body()
            );
        }
        return response;
    }

    private boolean dispatchPackagedInfo(
            ServerTransactionHandle transaction,
            SipRequest request
    ) throws SipHeaderValueException {
        if (request.method() != SipMethod.INFO || !request.headers().contains("Info-Package")) {
            return false;
        }
        if (SipHeaderValues.toTag(request.headers()).isEmpty()) {
            transaction.sendResponse(SipResponses.createResponse(
                    request,
                    481,
                    "Call/Transaction Does Not Exist",
                    "loomsip"
            ));
            return true;
        }
        InfoPackageHeaderValue infoPackage = SipHeaderValues.infoPackage(request.headers());
        InfoHandler handler = infoDispatcher == null
                ? null
                : infoDispatcher.find(infoPackage).orElse(null);
        if (handler == null) {
            sendUnsupportedInfoPackage(transaction, request);
            return true;
        }
        CompletionStage<InfoResponse> completion;
        try {
            completion = Objects.requireNonNull(
                    handler.onInfo(new InfoRequest(infoPackage, request.headers(), request.body())),
                    "INFO handler completion"
            );
        } catch (Throwable cause) {
            sendInfoHandlerFailure(transaction, request, cause);
            return true;
        }
        completion.whenComplete((response, failure) -> {
            if (failure != null) {
                sendInfoHandlerFailure(transaction, request, failure);
            } else if (response == null) {
                sendInfoHandlerFailure(
                        transaction,
                        request,
                        new IllegalStateException("INFO handler completed without a response")
                );
            } else {
                sendInfoResponse(transaction, request, response);
            }
        });
        return true;
    }

    private void sendUnsupportedInfoPackage(ServerTransactionHandle transaction, SipRequest request) {
        try {
            SipResponse base = SipResponses.createResponse(request, 469, "Bad Info Package");
            SipHeaders headers = base.headers();
            if (infoDispatcher != null && !infoDispatcher.supportedPackages().isEmpty()) {
                headers = headers.withReplaced(
                        "Recv-Info",
                        new RecvInfoHeaderValue(infoDispatcher.supportedPackages()).wireValue()
                );
            }
            transaction.sendResponse(new SipResponse(
                    base.version(),
                    base.statusCode(),
                    base.reasonPhrase(),
                    headers,
                    base.body()
            ));
        } catch (Throwable cause) {
            reportServerFailure("failed to reject unsupported INFO package", cause);
        }
    }

    private void sendInfoResponse(
            ServerTransactionHandle transaction,
            SipRequest request,
            InfoResponse response
    ) {
        try {
            SipResponse base = SipResponses.createResponse(
                    request,
                    response.statusCode(),
                    response.reasonPhrase()
            );
            SipHeaders headers = base.headers().toBuilder()
                    .addAll(response.additionalHeaders().entries())
                    .build();
            transaction.sendResponse(new SipResponse(
                    base.version(),
                    base.statusCode(),
                    base.reasonPhrase(),
                    headers,
                    response.body()
            ));
        } catch (Throwable cause) {
            sendInfoHandlerFailure(transaction, request, cause);
        }
    }

    private void sendInfoHandlerFailure(
            ServerTransactionHandle transaction,
            SipRequest request,
            Throwable cause
    ) {
        reportServerFailure("INFO handler failed", cause);
        try {
            transaction.sendResponse(SipResponses.createResponse(request, 500, "Server Internal Error"));
        } catch (Throwable responseFailure) {
            reportServerFailure("failed to send INFO handler failure response", responseFailure);
        }
    }

    private final class ClientSide implements InviteClientListener {

        @Override
        public void onResponse(
                InviteClientHandle transaction,
                SipResponse response,
                TransportContext context
        ) {
            try {
                Optional<DialogHandle> dialog = processClientResponse(
                        transaction.originalRequest(),
                        response
                );
                if (dialog.isPresent()) {
                    configureSessionTimer(dialog.orElseThrow(), response);
                }
                handleSessionRefreshFinal(transaction.originalRequest(), response);
                if (response.statusCode() >= 200
                        && response.statusCode() < 300
                        && dialog.isPresent()
                        && dialogs.reliabilityEnabled()) {
                    DialogAckTransmission transmission = awaitValue(dialogs.prepareUacAck(
                            dialog.orElseThrow().id(),
                            transaction.originalRequest(),
                            response
                    ));
                    try {
                        awaitValue(dialogs.sendUacAck(transmission, context.protocol()));
                    } catch (Throwable cause) {
                        long cseq = SipHeaderValues.cseq(
                                transaction.originalRequest().headers()
                        ).sequenceNumber();
                        dialogs.reportReliabilityFailure(
                                new DialogInviteKey(dialog.orElseThrow().id(), cseq),
                                transmission.ack(),
                                cause
                        );
                        throw cause;
                    }
                }
                if (dialog.isPresent()
                        && reliableProvisionals != null
                        && response.statusCode() > 100
                        && response.statusCode() < 200
                        && SipExtensionSupport.contains(
                        response.headers(),
                        "Require",
                        SipExtensionSupport.RELIABLE_PROVISIONAL
                )) {
                    Optional<org.loomsip.message.header.RAckHeaderValue> rack = awaitValue(
                            reliableProvisionals.receiveUacResponse(
                                    dialog.orElseThrow().id(),
                                    transaction.originalRequest(),
                                    response
                            )
                    );
                    rack.ifPresent(value -> dialog.orElseThrow()
                            .sendPrack(value, SipHeaders.empty(), SipBody.empty())
                            .whenComplete((ignored, failure) -> {
                                if (failure != null) {
                                    reportClientFailure(
                                            "failed to create PRACK for reliable provisional response",
                                            failure
                                    );
                                }
                            }));
                }
            } catch (Throwable cause) {
                failSessionRefresh(transaction.originalRequest(), cause);
                reportClientFailure("failed to bridge INVITE client response", cause);
            }
            clientDelegate.onResponse(transaction, response, context);
        }

        @Override
        public void onTimeout(InviteClientHandle transaction, SipTimer timer) {
            failSessionRefresh(
                    transaction.originalRequest(),
                    new IllegalStateException("Session refresh timed out on Timer " + timer)
            );
            clientDelegate.onTimeout(transaction, timer);
        }

        @Override
        public void onTransportFailure(InviteClientHandle transaction, Throwable cause) {
            failSessionRefresh(transaction.originalRequest(), cause);
            clientDelegate.onTransportFailure(transaction, cause);
        }

        @Override
        public void onTerminated(InviteClientHandle transaction) {
            try {
                cleanupClientEarlyDialogs(
                        transaction.originalRequest(),
                        DialogTerminationReason.INVITE_TRANSACTION_TERMINATED
                );
                releaseClientAckCache(transaction.originalRequest());
            } catch (Throwable cause) {
                reportClientFailure("failed to clean up UAC Early Dialogs", cause);
            }
            clientDelegate.onTerminated(transaction);
        }

        @Override
        public void onLayerError(Throwable cause) {
            clientDelegate.onLayerError(cause);
        }
    }

    private final class ServerSide implements InviteServerListener {

        @Override
        public void onInvite(
                InviteServerHandle transaction,
                SipRequest request,
                TransportContext context
        ) {
            try {
                if (reliableProvisionals == null && SipExtensionSupport.contains(
                        request.headers(),
                        "Require",
                        SipExtensionSupport.RELIABLE_PROVISIONAL
                )) {
                    SipResponse base = SipResponses.createResponse(
                            request,
                            420,
                            "Bad Extension",
                            SipHeaderValues.toTag(request.headers()).orElse("loomsip")
                    );
                    transaction.sendResponse(new SipResponse(
                            base.version(),
                            base.statusCode(),
                            base.reasonPhrase(),
                            base.headers().toBuilder().add(
                                    "Unsupported",
                                    SipExtensionSupport.RELIABLE_PROVISIONAL
                            ).build(),
                            base.body()
                    ));
                    return;
                }
                if (SipHeaderValues.toTag(request.headers()).isPresent()) {
                    DialogId id = DialogId.from(request.headers(), DialogRole.UAS);
                    await(dialogs.receiveInDialogRequest(id, request));
                }
            } catch (Throwable cause) {
                rejectInDialogRequest(transaction, request, cause);
                reportServerFailure("failed to route in-Dialog re-INVITE", cause);
                return;
            }
            BridgedServerHandle bridged = new BridgedServerHandle(transaction, request, context);
            BridgedServerHandle existing = serverHandles.putIfAbsent(transaction, bridged);
            serverDelegate.onInvite(existing == null ? bridged : existing, request, context);
        }

        @Override
        public void onCancel(
                InviteServerHandle transaction,
                SipRequest cancel,
                TransportContext context
        ) {
            serverDelegate.onCancel(serverHandle(transaction), cancel, context);
        }

        @Override
        public void onAck(
                InviteServerHandle transaction,
                SipRequest ack,
                TransportContext context
        ) {
            serverDelegate.onAck(serverHandle(transaction), ack, context);
        }

        @Override
        public void onUnmatchedAck(SipRequest ack, TransportContext context) {
            boolean matched = false;
            if (dialogs.reliabilityEnabled()) {
                try {
                    DialogId id = DialogId.from(ack.headers(), DialogRole.UAS);
                    matched = awaitValue(dialogs.receiveAck(id, ack, context));
                } catch (Throwable cause) {
                    reportServerFailure("failed to route Dialog ACK", cause);
                }
            }
            if (!matched) {
                serverDelegate.onUnmatchedAck(ack, context);
            }
        }

        @Override
        public void onTimeout(InviteServerHandle transaction, SipTimer timer) {
            serverDelegate.onTimeout(serverHandle(transaction), timer);
        }

        @Override
        public void onTransportFailure(InviteServerHandle transaction, Throwable cause) {
            InviteServerHandle selected = serverHandle(transaction);
            if (selected instanceof BridgedServerHandle bridged) {
                bridged.releaseSuccessReliability();
            }
            serverDelegate.onTransportFailure(selected, cause);
        }

        @Override
        public void onTerminated(InviteServerHandle transaction) {
            BridgedServerHandle bridged = serverHandles.remove(transaction);
            if (bridged != null) {
                try {
                    bridged.dialogIds.forEach(id -> cleanupDialog(
                            id,
                            DialogTerminationReason.INVITE_TRANSACTION_TERMINATED
                    ));
                } catch (Throwable cause) {
                    reportServerFailure("failed to clean up UAS Early Dialogs", cause);
                }
                serverDelegate.onTerminated(bridged);
            } else {
                serverDelegate.onTerminated(transaction);
            }
        }

        @Override
        public void onLayerError(Throwable cause) {
            serverDelegate.onLayerError(cause);
        }

        private InviteServerHandle serverHandle(InviteServerHandle transaction) {
            InviteServerHandle bridged = serverHandles.get(transaction);
            return bridged == null ? transaction : bridged;
        }
    }

    private final class NonInviteClientSide implements NonInviteClientListener {

        @Override
        public void onResponse(
                ClientTransactionHandle transaction,
                SipResponse response,
                TransportContext context
        ) {
            try {
                if (response.statusCode() >= 200
                        && response.statusCode() < 300
                        && response.headers().contains("Session-Expires")) {
                    DialogId id = DialogId.from(response.headers(), DialogRole.UAC);
                    Optional<DialogHandle> dialog = dialogs.find(id);
                    if (dialog.isPresent()) {
                        configureSessionTimer(dialog.orElseThrow(), response);
                    }
                }
                handleSessionRefreshFinal(transaction.originalRequest(), response);
            } catch (Throwable cause) {
                failSessionRefresh(transaction.originalRequest(), cause);
                reportClientFailure("failed to configure Session Timer from Non-INVITE response", cause);
            }
            nonInviteClientDelegate.onResponse(transaction, response, context);
        }

        @Override
        public void onTimeout(ClientTransactionHandle transaction, SipTimer timer) {
            failSessionRefresh(
                    transaction.originalRequest(),
                    new IllegalStateException("Session refresh timed out on Timer " + timer)
            );
            nonInviteClientDelegate.onTimeout(transaction, timer);
        }

        @Override
        public void onTransportFailure(ClientTransactionHandle transaction, Throwable cause) {
            failSessionRefresh(transaction.originalRequest(), cause);
            nonInviteClientDelegate.onTransportFailure(transaction, cause);
        }

        @Override
        public void onTerminated(ClientTransactionHandle transaction) {
            nonInviteClientDelegate.onTerminated(transaction);
        }

        @Override
        public void onLayerError(Throwable cause) {
            nonInviteClientDelegate.onLayerError(cause);
        }
    }

    private final class NonInviteServerSide implements NonInviteServerListener {

        @Override
        public void onRequest(
                ServerTransactionHandle transaction,
                SipRequest request,
                TransportContext context
        ) {
            // RFC 3265 subscription correlation belongs to SubscriptionManager, not DialogManager.
            if (SipMethod.NOTIFY.equals(request.method()) || SipMethod.SUBSCRIBE.equals(request.method())) {
                nonInviteServerDelegate.onRequest(transaction, request, context);
                return;
            }
            SessionTimerNegotiator.NegotiatedSessionTimer sessionNegotiation = null;
            try {
                if (SipMethod.UPDATE.equals(request.method())
                        && request.headers().contains("Session-Expires")) {
                    try {
                        sessionNegotiation = new SessionTimerNegotiator().negotiate(
                                SipHeaderValues.sessionExpires(request.headers()),
                                SessionTimerPolicy.DEFAULT
                        );
                    } catch (SessionIntervalTooSmallException tooSmall) {
                        SipResponse base = SipResponses.createResponse(
                                request,
                                422,
                                "Session Interval Too Small",
                                SipHeaderValues.toTag(request.headers()).orElse("loomsip")
                        );
                        transaction.sendResponse(new SipResponse(
                                base.version(),
                                base.statusCode(),
                                base.reasonPhrase(),
                                base.headers().toBuilder()
                                        .add("Min-SE", Integer.toString(tooSmall.minimumSeconds()))
                                        .build(),
                                base.body()
                        ));
                        return;
                    }
                }
                if (SipMethod.PRACK.equals(request.method()) && reliableProvisionals != null) {
                    DialogId id = DialogId.from(request.headers(), DialogRole.UAS);
                    PrackValidation validation = awaitValue(reliableProvisionals.acceptPrack(id, request));
                    if (validation != PrackValidation.ACCEPTED) {
                        transaction.sendResponse(SipResponses.createResponse(
                                request,
                                481,
                                "Call/Transaction Does Not Exist",
                                SipHeaderValues.toTag(request.headers()).orElse("loomsip")
                        ));
                        return;
                    }
                }
                if (SipHeaderValues.toTag(request.headers()).isPresent()) {
                    DialogId id = DialogId.from(request.headers(), DialogRole.UAS);
                    await(dialogs.receiveInDialogRequest(id, request));
                }
                if (dispatchPackagedInfo(transaction, request)) {
                    return;
                }
            } catch (Throwable cause) {
                rejectInDialogRequest(transaction, request, cause);
                reportServerFailure("failed to route in-Dialog Non-INVITE request", cause);
                return;
            }
            ServerTransactionHandle selected = sessionNegotiation == null
                    ? transaction
                    : new SessionTimerServerHandle(transaction, request, sessionNegotiation);
            nonInviteServerDelegate.onRequest(selected, request, context);
        }

        @Override
        public void onTransportFailure(ServerTransactionHandle transaction, Throwable cause) {
            nonInviteServerDelegate.onTransportFailure(transaction, cause);
        }

        @Override
        public void onTerminated(ServerTransactionHandle transaction) {
            nonInviteServerDelegate.onTerminated(transaction);
        }

        @Override
        public void onLayerError(Throwable cause) {
            nonInviteServerDelegate.onLayerError(cause);
        }
    }

    private final class SessionTimerServerHandle implements ServerTransactionHandle {

        private final ServerTransactionHandle delegate;
        private final SipRequest request;
        private final SessionTimerNegotiator.NegotiatedSessionTimer negotiated;

        private SessionTimerServerHandle(
                ServerTransactionHandle delegate,
                SipRequest request,
                SessionTimerNegotiator.NegotiatedSessionTimer negotiated
        ) {
            this.delegate = delegate;
            this.request = request;
            this.negotiated = negotiated;
        }

        @Override
        public org.loomsip.transaction.TransactionKey key() {
            return delegate.key();
        }

        @Override
        public org.loomsip.transaction.noninvite.NonInviteServerState state() {
            return delegate.state();
        }

        @Override
        public void sendResponse(SipResponse response) {
            SipResponse selected = response;
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                try {
                    selected = new SipResponse(
                            response.version(),
                            response.statusCode(),
                            response.reasonPhrase(),
                            response.headers().withReplaced(
                                    "Session-Expires",
                                    new SessionExpiresHeaderValue(
                                            negotiated.intervalSeconds(),
                                            Optional.of(negotiated.refresher())
                                    ).wireValue()
                            ),
                            response.body()
                    );
                    DialogId id = DialogId.from(request.headers(), DialogRole.UAS);
                    await(dialogs.configureSessionTimer(
                            id,
                            negotiated,
                            negotiated.refresher() == SessionRefresher.UAS
                    ));
                } catch (Throwable cause) {
                    reportServerFailure("failed to configure UAS Session Timer", cause);
                }
            }
            delegate.sendResponse(selected);
        }

        @Override
        public CompletionStage<Void> terminated() {
            return delegate.terminated();
        }
    }

    private final class BridgedServerHandle implements InviteServerHandle {

        private final InviteServerHandle delegate;
        private final SipRequest invite;
        private final TransportEndpoint responseTarget;
        private final TransportReliability reliability;
        private final Set<DialogId> dialogIds = ConcurrentHashMap.newKeySet();
        private final Object responseMonitor = new Object();

        private BridgedServerHandle(
                InviteServerHandle delegate,
                SipRequest invite,
                TransportContext context
        ) {
            this.delegate = Objects.requireNonNull(delegate, "delegate");
            this.invite = Objects.requireNonNull(invite, "invite");
            Objects.requireNonNull(context, "context");
            responseTarget = new TransportEndpoint(context.protocol(), context.remoteAddress());
            reliability = TransportReliability.from(context.protocol());
        }

        @Override
        public org.loomsip.transaction.TransactionKey key() {
            return delegate.key();
        }

        @Override
        public org.loomsip.transaction.invite.InviteServerState state() {
            return delegate.state();
        }

        @Override
        public void sendResponse(SipResponse response) {
            Objects.requireNonNull(response, "response");
            synchronized (responseMonitor) {
                try {
                    SipResponse selected = response;
                    DialogId reliableDialogId = null;
                    if (reliableProvisionals != null
                            && response.statusCode() > 100
                            && response.statusCode() < 200
                            && SipExtensionSupport.contains(
                            response.headers(),
                            "Require",
                            SipExtensionSupport.RELIABLE_PROVISIONAL
                    )) {
                        reliableDialogId = DialogId.from(response.headers(), DialogRole.UAS);
                        java.util.concurrent.atomic.AtomicReference<SipResponse> retransmit =
                                new java.util.concurrent.atomic.AtomicReference<>();
                        selected = awaitValue(reliableProvisionals.registerUasResponse(
                                reliableDialogId,
                                invite,
                                response,
                                reliability,
                                () -> delegate.sendResponse(retransmit.get())
                        ));
                        retransmit.set(selected);
                    }
                    Optional<DialogHandle> responseDialog = processServerResponse(this, selected);
                    if (responseDialog.isPresent()) {
                        configureSessionTimer(responseDialog.orElseThrow(), selected);
                    }
                    delegate.sendResponse(selected);
                    if (reliableProvisionals != null
                            && selected.statusCode() >= 200
                            && SipHeaderValues.toTag(selected.headers()).isPresent()) {
                        DialogId id = DialogId.from(selected.headers(), DialogRole.UAS);
                        reliableProvisionals.release(id);
                    }
                } catch (Throwable cause) {
                    if (response.statusCode() >= 200 && response.statusCode() < 300) {
                        releaseSuccessReliability();
                    }
                    reportServerFailure("failed to bridge INVITE server response", cause);
                }
            }
        }

        private void releaseSuccessReliability() {
            if (!dialogs.reliabilityEnabled()) {
                return;
            }
            try {
                long cseq = SipHeaderValues.cseq(invite.headers()).sequenceNumber();
                for (DialogId id : dialogIds) {
                    await(dialogs.releaseUasExchange(new DialogInviteKey(id, cseq)));
                }
            } catch (Throwable cause) {
                reportServerFailure("failed to release UAS 2xx reliability state", cause);
            }
        }

        @Override
        public CompletionStage<Void> terminated() {
            return delegate.terminated();
        }
    }
}
