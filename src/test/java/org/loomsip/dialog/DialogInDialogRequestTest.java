package org.loomsip.dialog;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.loomsip.info.InfoDispatcher;
import org.loomsip.info.InfoRequest;
import org.loomsip.info.InfoResponse;
import org.loomsip.message.SipBody;
import org.loomsip.message.SipHeaders;
import org.loomsip.message.SipMethod;
import org.loomsip.message.SipRequest;
import org.loomsip.message.SipResponse;
import org.loomsip.message.SipResponses;
import org.loomsip.message.SipUri;
import org.loomsip.message.header.SentBy;
import org.loomsip.message.header.InfoPackageHeaderValue;
import org.loomsip.message.header.RAckHeaderValue;
import org.loomsip.message.header.SipHeaderValues;
import org.loomsip.message.header.SessionRefresher;
import org.loomsip.transaction.TransactionKey;
import org.loomsip.transaction.TransactionMessageSender;
import org.loomsip.transaction.invite.InviteClientHandle;
import org.loomsip.transaction.invite.InviteClientState;
import org.loomsip.transaction.invite.InviteServerHandle;
import org.loomsip.transaction.invite.InviteServerListener;
import org.loomsip.transaction.invite.InviteServerState;
import org.loomsip.transaction.noninvite.ClientTransactionHandle;
import org.loomsip.transaction.noninvite.NonInviteClientState;
import org.loomsip.transaction.noninvite.NonInviteServerState;
import org.loomsip.transaction.noninvite.ServerTransactionHandle;
import org.loomsip.transaction.timer.SipTimerConfig;
import org.loomsip.transaction.timer.SipTimer;
import org.loomsip.transaction.timer.VirtualSipScheduler;
import org.loomsip.transport.SendResult;
import org.loomsip.transport.TransportContext;
import org.loomsip.transport.TransportEndpoint;
import org.loomsip.transport.TransportProtocol;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Timeout(10)
class DialogInDialogRequestTest {

    @Test
    void reInviteUsesDialogIdentityRouteAndNextLocalSequence() throws Exception {
        try (TestRig rig = new TestRig()) {
            DialogHandle dialog = rig.createDialog();
            SipBody body = SipBody.of("v=0".getBytes(StandardCharsets.US_ASCII));

            InviteClientHandle transaction = await(dialog.sendReInvite(
                    SipHeaders.builder()
                            .add("Contact", "<sip:alice@new-client.example.com>")
                            .add("Content-Type", "application/sdp")
                            .build(),
                    body
            ));

            assertEquals(InviteClientState.CALLING, transaction.state());
            SipRequest request = rig.dispatcher.invites.getFirst();
            assertEquals(SipMethod.INVITE, request.method());
            assertEquals(SipUri.parse("sip:bob@server.example.com"), request.requestUri());
            assertEquals(11, SipHeaderValues.cseq(request.headers()).sequenceNumber());
            assertEquals("call@example.com", SipHeaderValues.callId(request.headers()));
            assertEquals("local-tag", SipHeaderValues.fromTag(request.headers()).orElseThrow());
            assertEquals("remote-tag", SipHeaderValues.toTag(request.headers()).orElseThrow());
            assertTrue(request.headers().firstValue("Via").orElseThrow()
                    .contains("branch=z9hG4bK-dialog-1"));
            assertEquals(body, request.body());
            assertEquals(11, dialog.snapshot().localCSeq());
            assertEquals(rig.remote, rig.dispatcher.targets.getFirst());
        }
    }

    @Test
    void strictRouteAndManagedHeaderValidationAreAppliedBeforeDispatch() throws Exception {
        try (TestRig rig = new TestRig(true)) {
            DialogHandle dialog = rig.createDialog();

            IllegalArgumentException failure = assertThrows(
                    IllegalArgumentException.class,
                    () -> await(dialog.sendReInvite(
                            SipHeaders.builder().add("Call-ID", "override").build(),
                            SipBody.empty()
                    ))
            );
            assertTrue(failure.getMessage().contains("Dialog manages header"));
            assertTrue(rig.dispatcher.invites.isEmpty());
            assertEquals(10, dialog.snapshot().localCSeq());

            await(dialog.sendReInvite(SipHeaders.empty(), SipBody.empty()));
            SipRequest request = rig.dispatcher.invites.getFirst();
            assertEquals(SipUri.parse("sip:strict-proxy.example.com"), request.requestUri());
            assertEquals(List.of(
                    "<sip:edge.example.com;lr>",
                    "<sip:bob@server.example.com>"
            ), request.headers().all("Route").stream().map(header -> header.value()).toList());
        }
    }

    @Test
    void inboundReInviteUpdatesRemoteSequenceAndTargetWithoutChangingRouteSet() throws Exception {
        try (TestRig rig = new TestRig()) {
            DialogHandle dialog = rig.createDialog();
            List<org.loomsip.message.header.RouteHeaderValue> originalRoutes = dialog.snapshot().routeSet();

            await(rig.manager.receiveInDialogRequest(dialog.id(), inbound(SipMethod.INVITE, 21, true)));

            assertEquals(21, dialog.snapshot().remoteCSeq());
            assertEquals(
                    Optional.of(SipUri.parse("sip:bob@refreshed.example.com")),
                    dialog.snapshot().remoteTarget()
            );
            assertEquals(originalRoutes, dialog.snapshot().routeSet());

            DialogRequestRejectedException rejected = assertThrows(
                    DialogRequestRejectedException.class,
                    () -> await(
                    rig.manager.receiveInDialogRequest(dialog.id(), inbound(SipMethod.INVITE, 21, true))
                    )
            );
            assertEquals(500, rejected.statusCode());
            assertEquals(21, dialog.snapshot().remoteCSeq());
        }
    }

    @Test
    void localAndRemoteByeTerminateAndRemoveDialog() throws Exception {
        try (TestRig localRig = new TestRig()) {
            DialogHandle dialog = localRig.createDialog();

            ClientTransactionHandle transaction = await(dialog.sendBye());

            assertEquals(NonInviteClientState.TRYING, transaction.state());
            assertEquals(SipMethod.BYE, localRig.dispatcher.nonInvites.getFirst().method());
            assertEquals(DialogState.TERMINATED, dialog.snapshot().state());
            assertTrue(localRig.manager.find(dialog.id()).isEmpty());
        }

        try (TestRig remoteRig = new TestRig()) {
            DialogHandle dialog = remoteRig.createDialog();

            await(remoteRig.manager.receiveInDialogRequest(
                    dialog.id(), inbound(SipMethod.BYE, 21, false)
            ));

            assertEquals(DialogState.TERMINATED, dialog.snapshot().state());
            assertTrue(remoteRig.manager.find(dialog.id()).isEmpty());
        }
    }

    @Test
    void genericInfoUsesDialogRoutingAndMonotonicSequence() throws Exception {
        try (TestRig rig = new TestRig()) {
            DialogHandle dialog = rig.createDialog();
            SipBody body = SipBody.of("Signal=5".getBytes(StandardCharsets.US_ASCII));

            ClientTransactionHandle transaction = await(dialog.sendRequest(
                    SipMethod.INFO,
                    SipHeaders.builder()
                            .add("Content-Type", "application/dtmf-relay")
                            .build(),
                    body
            ));

            assertEquals(NonInviteClientState.TRYING, transaction.state());
            SipRequest request = rig.dispatcher.nonInvites.getFirst();
            assertEquals(SipMethod.INFO, request.method());
            assertEquals(11, SipHeaderValues.cseq(request.headers()).sequenceNumber());
            assertEquals("application/dtmf-relay", request.headers()
                    .firstValue("Content-Type").orElseThrow());
            assertEquals(body, request.body());
            assertEquals(rig.remote, rig.dispatcher.targets.getFirst());
            assertEquals(11, dialog.snapshot().localCSeq());
        }
    }

    @Test
    void prackUsesEarlyDialogRoutingAndDedicatedRackHeader() throws Exception {
        try (TestRig rig = new TestRig()) {
            DialogHandle dialog = rig.createEarlyDialog();
            RAckHeaderValue rack = new RAckHeaderValue(7, 10, SipMethod.INVITE);

            ClientTransactionHandle transaction = await(dialog.sendPrack(
                    rack,
                    SipHeaders.empty(),
                    SipBody.empty()
            ));

            assertEquals(NonInviteClientState.TRYING, transaction.state());
            SipRequest request = rig.dispatcher.nonInvites.getFirst();
            assertEquals(SipMethod.PRACK, request.method());
            assertEquals("7 10 INVITE", request.headers().firstValue("RAck").orElseThrow());
            assertEquals(11, SipHeaderValues.cseq(request.headers()).sequenceNumber());
            assertEquals(DialogState.EARLY, dialog.snapshot().state());
            assertThrows(IllegalStateException.class, () -> await(dialog.sendRequest(
                    SipMethod.INFO,
                    SipHeaders.empty(),
                    SipBody.empty()
            )));
        }
    }

    @Test
    void updateIsAllowedInEarlyDialogAndRefreshesRemoteTarget() throws Exception {
        try (TestRig rig = new TestRig()) {
            DialogHandle dialog = rig.createEarlyDialog();

            ClientTransactionHandle transaction = await(dialog.sendUpdate(
                    SipHeaders.builder().add("Contact", "<sip:alice@update-client.example.com>").build(),
                    SipBody.empty()
            ));

            assertEquals(NonInviteClientState.TRYING, transaction.state());
            SipRequest request = rig.dispatcher.nonInvites.getFirst();
            assertEquals(SipMethod.UPDATE, request.method());
            assertEquals(11, SipHeaderValues.cseq(request.headers()).sequenceNumber());
            assertEquals(DialogState.EARLY, dialog.snapshot().state());

            await(rig.manager.receiveInDialogRequest(
                    dialog.id(),
                    inbound(SipMethod.UPDATE, 21, true)
            ));
            assertEquals(Optional.of(SipUri.parse("sip:bob@refreshed.example.com")),
                    dialog.snapshot().remoteTarget());
        }
    }

    @Test
    void sessionTimerRefreshAndExpiryAreSerializedThroughDialog() throws Exception {
        try (TestRig rig = new TestRig()) {
            DialogHandle dialog = rig.createDialog();
            await(rig.manager.configureSessionTimer(
                    dialog.id(),
                    new SessionTimerNegotiator.NegotiatedSessionTimer(
                            120,
                            SessionRefresher.UAC,
                            SessionRefreshMethod.UPDATE
                    ),
                    true
            ));

            rig.scheduler.advanceBy(java.time.Duration.ofSeconds(60));

            SipRequest refresh = rig.dispatcher.nonInvites.getFirst();
            assertEquals(SipMethod.UPDATE, refresh.method());
            assertEquals("120;refresher=uac",
                    refresh.headers().firstValue("Session-Expires").orElseThrow());
            assertEquals(11, SipHeaderValues.cseq(refresh.headers()).sequenceNumber());
        }

        try (TestRig rig = new TestRig()) {
            DialogHandle dialog = rig.createDialog();
            await(rig.manager.configureSessionTimer(
                    dialog.id(),
                    new SessionTimerNegotiator.NegotiatedSessionTimer(
                            120,
                            SessionRefresher.UAS,
                            SessionRefreshMethod.UPDATE
                    ),
                    false
            ));

            rig.scheduler.advanceBy(java.time.Duration.ofSeconds(120));

            assertEquals(DialogState.TERMINATED, dialog.snapshot().state());
            assertTrue(rig.manager.find(dialog.id()).isEmpty());
        }
    }

    @Test
    void sendInfoUsesManagedPackageHeaderAndDialogRequestState() throws Exception {
        try (TestRig rig = new TestRig()) {
            DialogHandle dialog = rig.createDialog();
            SipBody body = SipBody.of("floor-request".getBytes(StandardCharsets.US_ASCII));

            ClientTransactionHandle transaction = await(dialog.sendInfo(new InfoRequest(
                    new InfoPackageHeaderValue("conference"),
                    SipHeaders.builder().add("Content-Type", "application/conference-info+xml").build(),
                    body
            )));

            SipRequest request = rig.dispatcher.nonInvites.getFirst();
            assertEquals(NonInviteClientState.TRYING, transaction.state());
            assertEquals(SipMethod.INFO, request.method());
            assertEquals("conference", request.headers().firstValue("Info-Package").orElseThrow());
            assertEquals("application/conference-info+xml", request.headers().firstValue("Content-Type").orElseThrow());
            assertEquals(body, request.body());
            assertEquals(11, SipHeaderValues.cseq(request.headers()).sequenceNumber());
        }
    }

    @Test
    void sendInfoRejectsCallerPackageOverrideAndEarlyDialog() throws Exception {
        try (TestRig rig = new TestRig()) {
            DialogHandle dialog = rig.createDialog();
            InfoRequest overridden = new InfoRequest(
                    new InfoPackageHeaderValue("conference"),
                    SipHeaders.builder().add("Info-Package", "override").build(),
                    SipBody.empty()
            );
            assertThrows(IllegalArgumentException.class, () -> await(dialog.sendInfo(overridden)));
        }

        try (TestRig rig = new TestRig()) {
            DialogHandle dialog = rig.createEarlyDialog();
            assertThrows(IllegalStateException.class, () -> await(dialog.sendInfo(new InfoRequest(
                    new InfoPackageHeaderValue("conference"),
                    SipHeaders.empty(),
                    SipBody.empty()
            ))));
        }
    }

    @Test
    void referRemainsGenericConfirmedDialogRequestWithoutSubscriptionSemantics() throws Exception {
        try (TestRig rig = new TestRig()) {
            DialogHandle dialog = rig.createDialog();

            await(dialog.sendRequest(
                    SipMethod.REFER,
                    SipHeaders.builder().add("Refer-To", "<sip:carol@example.com>").build(),
                    SipBody.empty()
            ));

            SipRequest request = rig.dispatcher.nonInvites.getFirst();
            assertEquals(SipMethod.REFER, request.method());
            assertEquals("<sip:carol@example.com>", request.headers().firstValue("Refer-To").orElseThrow());
            assertEquals(11, SipHeaderValues.cseq(request.headers()).sequenceNumber());
        }
    }

    @Test
    void uasRejectsTooSmallUpdateBeforeApplicationCallback() throws Exception {
        try (TestRig rig = new TestRig()) {
            DialogHandle dialog = rig.createDialog();
            AtomicInteger callbacks = new AtomicInteger();
            RecordingServerTransaction transaction = new RecordingServerTransaction();
            DialogTransactionBridge bridge = bridge(rig, (selected, request, context) ->
                    callbacks.incrementAndGet());

            bridge.nonInviteServerListener().onRequest(
                    transaction,
                    withSessionExpires(inbound(SipMethod.UPDATE, 21, true), "60;refresher=uac"),
                    context(rig)
            );

            assertEquals(0, callbacks.get());
            assertEquals(422, transaction.response.get().statusCode());
            assertEquals("90", transaction.response.get().headers().firstValue("Min-SE").orElseThrow());
            assertEquals(DialogState.CONFIRMED, dialog.snapshot().state());
        }
    }

    @Test
    void uasStartsUpdateSessionTimerOnlyWhenApplicationSendsSuccess() throws Exception {
        try (TestRig rig = new TestRig()) {
            DialogHandle dialog = rig.createDialog();
            AtomicReference<ServerTransactionHandle> applicationHandle = new AtomicReference<>();
            RecordingServerTransaction transaction = new RecordingServerTransaction();
            DialogTransactionBridge bridge = bridge(rig, (selected, request, context) ->
                    applicationHandle.set(selected));
            SipRequest update = withSessionExpires(
                    inbound(SipMethod.UPDATE, 21, true),
                    "120;refresher=uac"
            );

            bridge.nonInviteServerListener().onRequest(transaction, update, context(rig));
            rig.scheduler.advanceBy(java.time.Duration.ofSeconds(120));
            assertEquals(DialogState.CONFIRMED, dialog.snapshot().state());

            applicationHandle.get().sendResponse(SipResponses.createResponse(update, 200, "OK"));
            assertEquals("120;refresher=uac",
                    transaction.response.get().headers().firstValue("Session-Expires").orElseThrow());
            rig.scheduler.advanceBy(java.time.Duration.ofSeconds(120));
            assertEquals(DialogState.TERMINATED, dialog.snapshot().state());
        }
    }

    @Test
    void uacSuccessReplacesGenerationAnd422RetriesOnlyOnce() throws Exception {
        try (TestRig rig = new TestRig()) {
            DialogHandle dialog = rig.createDialog();
            await(rig.manager.configureSessionTimer(
                    dialog.id(),
                    negotiated(120),
                    true
            ));
            DialogTransactionBridge bridge = bridge(rig, (transaction, request, context) -> {
            });

            rig.scheduler.advanceBy(java.time.Duration.ofSeconds(60));
            ClientTransactionHandle first = rig.dispatcher.nonInviteHandles.getFirst();
            SipResponse success = withSessionExpires(
                    SipResponses.createResponse(first.originalRequest(), 200, "OK"),
                    "240;refresher=uac"
            );
            bridge.nonInviteClientListener().onResponse(first, success, context(rig));
            bridge.nonInviteClientListener().onTimeout(first, SipTimer.F);
            assertEquals(DialogState.CONFIRMED, dialog.snapshot().state());

            rig.scheduler.advanceBy(java.time.Duration.ofSeconds(60));
            assertEquals(1, rig.dispatcher.nonInvites.size());
            rig.scheduler.advanceBy(java.time.Duration.ofSeconds(60));
            assertEquals(2, rig.dispatcher.nonInvites.size());

            ClientTransactionHandle second = rig.dispatcher.nonInviteHandles.get(1);
            SipResponse first422 = responseWithHeader(
                    second.originalRequest(),
                    422,
                    "Session Interval Too Small",
                    "Min-SE",
                    "300"
            );
            bridge.nonInviteClientListener().onResponse(second, first422, context(rig));
            assertEquals(3, rig.dispatcher.nonInvites.size());
            assertEquals(13, SipHeaderValues.cseq(rig.dispatcher.nonInvites.get(2).headers()).sequenceNumber());

            ClientTransactionHandle retry = rig.dispatcher.nonInviteHandles.get(2);
            bridge.nonInviteClientListener().onResponse(
                    retry,
                    responseWithHeader(
                            retry.originalRequest(),
                            422,
                            "Session Interval Too Small",
                            "Min-SE",
                            "300"
                    ),
                    context(rig)
            );
            assertEquals(DialogState.TERMINATED, dialog.snapshot().state());
            assertEquals(3, rig.dispatcher.nonInvites.size());
        }
    }

    @Test
    void automaticRefreshTimeoutTerminatesDialog() throws Exception {
        try (TestRig rig = new TestRig()) {
            DialogHandle dialog = rig.createDialog();
            await(rig.manager.configureSessionTimer(dialog.id(), negotiated(120), true));
            DialogTransactionBridge bridge = bridge(rig, (transaction, request, context) -> {
            });

            rig.scheduler.advanceBy(java.time.Duration.ofSeconds(60));
            bridge.nonInviteClientListener().onTimeout(
                    rig.dispatcher.nonInviteHandles.getFirst(),
                    SipTimer.F
            );

            assertEquals(DialogState.TERMINATED, dialog.snapshot().state());
        }
    }

    @Test
    void genericApiRejectsMethodsWithDedicatedSemantics() throws Exception {
        try (TestRig rig = new TestRig()) {
            DialogHandle dialog = rig.createDialog();

            for (SipMethod method : List.of(
                    SipMethod.INVITE,
                    SipMethod.BYE,
                    SipMethod.ACK,
                    SipMethod.CANCEL
            )) {
                IllegalArgumentException failure = assertThrows(
                        IllegalArgumentException.class,
                        () -> await(dialog.sendRequest(method, SipHeaders.empty(), SipBody.empty()))
                );
                assertTrue(failure.getMessage().contains("dedicated"));
            }
            assertTrue(rig.dispatcher.nonInvites.isEmpty());
            assertEquals(10, dialog.snapshot().localCSeq());
        }
    }

    @Test
    void bridgeValidatesGenericInDialogRequestBeforeApplicationCallback() throws Exception {
        try (TestRig rig = new TestRig()) {
            DialogHandle dialog = rig.createDialog();
            AtomicInteger callbacks = new AtomicInteger();
            AtomicReference<org.loomsip.message.SipResponse> rejection = new AtomicReference<>();
            DialogTransactionBridge bridge = new DialogTransactionBridge(
                    rig.manager,
                    (transaction, response, context) -> {
                    },
                    (transaction, request, context) -> {
                    },
                    (transaction, response, context) -> {
                    },
                    (transaction, request, context) -> {
                        callbacks.incrementAndGet();
                        assertEquals(21, dialog.snapshot().remoteCSeq());
                    }
            );
            ServerTransactionHandle serverTransaction = new ServerTransactionHandle() {
                @Override
                public TransactionKey key() {
                    return null;
                }

                @Override
                public NonInviteServerState state() {
                    return NonInviteServerState.TRYING;
                }

                @Override
                public void sendResponse(org.loomsip.message.SipResponse response) {
                    rejection.set(response);
                }

                @Override
                public CompletionStage<Void> terminated() {
                    return CompletableFuture.completedFuture(null);
                }
            };

            bridge.nonInviteServerListener().onRequest(
                    serverTransaction,
                    inbound(SipMethod.INFO, 21, false),
                    new TransportContext(
                            TransportProtocol.UDP,
                            rig.remote.address(),
                            rig.remote.address()
                    )
            );

            assertEquals(1, callbacks.get());
            assertEquals(21, dialog.snapshot().remoteCSeq());
            assertEquals(null, rejection.get());
        }
    }

    @Test
    void bridgeDispatchesPackagedInfoAfterDialogValidation() throws Exception {
        try (TestRig rig = new TestRig()) {
            DialogHandle dialog = rig.createDialog();
            InfoDispatcher dispatcher = new InfoDispatcher();
            AtomicReference<InfoRequest> delivered = new AtomicReference<>();
            dispatcher.register(new InfoPackageHeaderValue("conference"), request -> {
                delivered.set(request);
                return CompletableFuture.completedFuture(new InfoResponse(
                        200,
                        "OK",
                        SipHeaders.builder().add("X-Info-Handled", "true").build(),
                        SipBody.of("accepted".getBytes(StandardCharsets.US_ASCII))
                ));
            });
            AtomicInteger fallbackCallbacks = new AtomicInteger();
            DialogTransactionBridge bridge = bridge(
                    rig,
                    (transaction, request, context) -> fallbackCallbacks.incrementAndGet(),
                    dispatcher
            );
            RecordingServerTransaction transaction = new RecordingServerTransaction();
            SipRequest request = withInfoPackage(inbound(SipMethod.INFO, 21, false), "conference");

            bridge.nonInviteServerListener().onRequest(transaction, request, context(rig));

            assertEquals(0, fallbackCallbacks.get());
            assertEquals(21, dialog.snapshot().remoteCSeq());
            assertEquals("conference", delivered.get().infoPackage().name());
            assertEquals(200, transaction.response.get().statusCode());
            assertEquals("true", transaction.response.get().headers().firstValue("X-Info-Handled").orElseThrow());
            assertEquals("accepted", new String(transaction.response.get().body().bytes(), StandardCharsets.US_ASCII));
        }
    }

    @Test
    void bridgeRejectsUnknownInfoPackageWithRecvInfo() throws Exception {
        try (TestRig rig = new TestRig()) {
            DialogHandle dialog = rig.createDialog();
            InfoDispatcher dispatcher = new InfoDispatcher();
            dispatcher.register(new InfoPackageHeaderValue("conference"),
                    request -> CompletableFuture.completedFuture(InfoResponse.ok()));
            AtomicInteger fallbackCallbacks = new AtomicInteger();
            DialogTransactionBridge bridge = bridge(
                    rig,
                    (transaction, request, context) -> fallbackCallbacks.incrementAndGet(),
                    dispatcher
            );
            RecordingServerTransaction transaction = new RecordingServerTransaction();

            bridge.nonInviteServerListener().onRequest(
                    transaction,
                    withInfoPackage(inbound(SipMethod.INFO, 21, false), "x-vendor"),
                    context(rig)
            );

            assertEquals(0, fallbackCallbacks.get());
            assertEquals(21, dialog.snapshot().remoteCSeq());
            assertEquals(469, transaction.response.get().statusCode());
            assertEquals("conference", transaction.response.get().headers().firstValue("Recv-Info").orElseThrow());
        }
    }

    @Test
    void bridgeRejectsPackagedInfoOutsideDialog() throws Exception {
        try (TestRig rig = new TestRig()) {
            InfoDispatcher dispatcher = new InfoDispatcher();
            dispatcher.register(new InfoPackageHeaderValue("conference"),
                    request -> CompletableFuture.completedFuture(InfoResponse.ok()));
            DialogTransactionBridge bridge = bridge(rig, (transaction, request, context) -> {
            }, dispatcher);
            RecordingServerTransaction transaction = new RecordingServerTransaction();
            SipRequest request = withInfoPackage(
                    inbound(SipMethod.INFO, 21, false),
                    "conference"
            );
            request = new SipRequest(
                    request.method(),
                    request.requestUri(),
                    request.version(),
                    request.headers().withReplaced("To", "<sip:alice@example.com>"),
                    request.body()
            );

            bridge.nonInviteServerListener().onRequest(transaction, request, context(rig));

            assertEquals(481, transaction.response.get().statusCode());
        }
    }

    @Test
    void bridgeConvertsInfoHandlerFailureToServerError() throws Exception {
        try (TestRig rig = new TestRig()) {
            InfoDispatcher dispatcher = new InfoDispatcher();
            dispatcher.register(new InfoPackageHeaderValue("conference"),
                    request -> CompletableFuture.failedFuture(new IllegalStateException("handler failure")));
            DialogTransactionBridge bridge = bridge(rig, (transaction, request, context) -> {
            }, dispatcher);
            RecordingServerTransaction transaction = new RecordingServerTransaction();

            rig.createDialog();
            bridge.nonInviteServerListener().onRequest(
                    transaction,
                    withInfoPackage(inbound(SipMethod.INFO, 21, false), "conference"),
                    context(rig)
            );

            assertEquals(500, transaction.response.get().statusCode());
        }
    }

    @Test
    void concurrentReInvitesReceiveUniqueMonotonicLocalSequences() throws Exception {
        try (TestRig rig = new TestRig();
                ExecutorService callers = Executors.newFixedThreadPool(8)) {
            DialogHandle dialog = rig.createDialog();
            List<Future<?>> operations = new ArrayList<>();
            for (int index = 0; index < 50; index++) {
                operations.add(callers.submit(() -> await(dialog.sendReInvite(
                        SipHeaders.empty(),
                        SipBody.empty()
                ))));
            }
            for (Future<?> operation : operations) {
                operation.get();
            }

            TreeSet<Long> sequences = new TreeSet<>();
            for (SipRequest request : rig.dispatcher.invites) {
                sequences.add(SipHeaderValues.cseq(request.headers()).sequenceNumber());
            }
            assertEquals(50, sequences.size());
            assertEquals(11L, sequences.iterator().next());
            assertEquals(60L, sequences.last());
            assertEquals(60, dialog.snapshot().localCSeq());
        }
    }

    @Test
    void bridgeRejectsStaleReInviteBeforeApplicationCallback() throws Exception {
        try (TestRig rig = new TestRig()) {
            DialogHandle dialog = rig.createDialog();
            AtomicReference<org.loomsip.message.SipResponse> rejection = new AtomicReference<>();
            AtomicInteger applicationInvites = new AtomicInteger();
            InviteServerListener application = new InviteServerListener() {
                @Override
                public void onInvite(
                        InviteServerHandle transaction,
                        SipRequest request,
                        TransportContext context
                ) {
                    applicationInvites.incrementAndGet();
                }
            };
            DialogTransactionBridge bridge = new DialogTransactionBridge(
                    rig.manager,
                    (transaction, response, context) -> {
                    },
                    application
            );
            InviteServerHandle transaction = new InviteServerHandle() {
                @Override
                public TransactionKey key() {
                    return null;
                }

                @Override
                public InviteServerState state() {
                    return InviteServerState.PROCEEDING;
                }

                @Override
                public void sendResponse(org.loomsip.message.SipResponse response) {
                    rejection.set(response);
                }

                @Override
                public CompletionStage<Void> terminated() {
                    return CompletableFuture.completedFuture(null);
                }
            };

            bridge.serverListener().onInvite(
                    transaction,
                    inbound(SipMethod.INVITE, 20, true),
                    new TransportContext(
                            TransportProtocol.UDP,
                            rig.remote.address(),
                            rig.remote.address()
                    )
            );

            assertEquals(500, rejection.get().statusCode());
            assertEquals("0", rejection.get().headers().firstValue("Retry-After").orElseThrow());
            assertEquals(0, applicationInvites.get());
            assertEquals(20, dialog.snapshot().remoteCSeq());
        }
    }

    private static SipRequest inbound(SipMethod method, long cseq, boolean contact) {
        SipHeaders.Builder headers = SipHeaders.builder()
                .add("Via", "SIP/2.0/UDP server.example.com;branch=z9hG4bK-remote-" + cseq)
                .add("Max-Forwards", "70")
                .add("From", "<sip:bob@example.com>;tag=remote-tag")
                .add("To", "<sip:alice@example.com>;tag=local-tag")
                .add("Call-ID", "call@example.com")
                .add("CSeq", cseq + " " + method.value());
        if (contact) {
            headers.add("Contact", "<sip:bob@refreshed.example.com>");
        }
        return new SipRequest(method, SipUri.parse("sip:alice@client.example.com"), headers.build());
    }

    private static SipRequest withSessionExpires(SipRequest request, String value) {
        return new SipRequest(
                request.method(),
                request.requestUri(),
                request.version(),
                request.headers().withReplaced("Session-Expires", value),
                request.body()
        );
    }

    private static SipRequest withInfoPackage(SipRequest request, String value) {
        return new SipRequest(
                request.method(),
                request.requestUri(),
                request.version(),
                request.headers().withReplaced("Info-Package", value),
                request.body()
        );
    }

    private static SipResponse withSessionExpires(SipResponse response, String value) {
        return new SipResponse(
                response.version(),
                response.statusCode(),
                response.reasonPhrase(),
                response.headers().withReplaced("Session-Expires", value),
                response.body()
        );
    }

    private static SipResponse responseWithHeader(
            SipRequest request,
            int status,
            String reason,
            String name,
            String value
    ) {
        SipResponse response = SipResponses.createResponse(request, status, reason);
        return new SipResponse(
                response.version(),
                response.statusCode(),
                response.reasonPhrase(),
                response.headers().withReplaced(name, value),
                response.body()
        );
    }

    private static SessionTimerNegotiator.NegotiatedSessionTimer negotiated(int intervalSeconds) {
        return new SessionTimerNegotiator.NegotiatedSessionTimer(
                intervalSeconds,
                SessionRefresher.UAC,
                SessionRefreshMethod.UPDATE
        );
    }

    private static DialogTransactionBridge bridge(
            TestRig rig,
            org.loomsip.transaction.noninvite.NonInviteServerListener serverListener
    ) {
        return new DialogTransactionBridge(
                rig.manager,
                (transaction, response, context) -> {
                },
                (transaction, request, context) -> {
                },
                (transaction, response, context) -> {
                },
                serverListener
        );
    }

    private static DialogTransactionBridge bridge(
            TestRig rig,
            org.loomsip.transaction.noninvite.NonInviteServerListener serverListener,
            InfoDispatcher infoDispatcher
    ) {
        return new DialogTransactionBridge(
                rig.manager,
                (transaction, response, context) -> {
                },
                (transaction, request, context) -> {
                },
                (transaction, response, context) -> {
                },
                serverListener,
                null,
                infoDispatcher
        );
    }

    private static TransportContext context(TestRig rig) {
        return new TransportContext(
                TransportProtocol.UDP,
                rig.remote.address(),
                rig.remote.address()
        );
    }

    private static <T> T await(CompletionStage<T> stage) {
        try {
            return stage.toCompletableFuture().join();
        } catch (java.util.concurrent.CompletionException exception) {
            if (exception.getCause() instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw exception;
        }
    }

    private static final class TestRig implements AutoCloseable {

        private final VirtualSipScheduler scheduler = new VirtualSipScheduler();
        private final RecordingDispatcher dispatcher = new RecordingDispatcher();
        private final TransportEndpoint remote;
        private final DialogManager manager;
        private final boolean strict;

        private TestRig() throws Exception {
            this(false);
        }

        private TestRig(boolean strict) throws Exception {
            this.strict = strict;
            remote = TransportEndpoint.udp(new InetSocketAddress(InetAddress.getLoopbackAddress(), 22061));
            AtomicInteger branches = new AtomicInteger();
            TransactionMessageSender sender = (message, target) -> CompletableFuture.completedFuture(
                    new SendResult(target, target, 1)
            );
            DialogRuntime dialogRuntime = new DialogRuntime(
                    sender,
                    (uri, protocol) -> CompletableFuture.completedFuture(remote),
                    scheduler,
                    SipTimerConfig.DEFAULT,
                    () -> "z9hG4bK-dialog-" + branches.incrementAndGet(),
                    Runnable::run
            );
            DialogRequestRuntime requestRuntime = new DialogRequestRuntime(
                    dialogRuntime,
                    DialogRequestProfile.udp(new SentBy("client.example.com", 5060)),
                    dispatcher
            );
            manager = new DialogManager(
                    new DialogConfig(4, 32, 16),
                    new DialogLifecycleListener() {
                    },
                    new InMemoryDialogRepository(4),
                    Runnable::run,
                    Runnable::run,
                    requestRuntime
            );
        }

        private DialogHandle createDialog() throws Exception {
            List<org.loomsip.message.header.RouteHeaderValue> routes = strict
                    ? List.of(
                    org.loomsip.message.header.DialogHeaderValues.routes(
                            SipHeaders.builder()
                                    .add("Route", "<sip:strict-proxy.example.com>")
                                    .add("Route", "<sip:edge.example.com;lr>")
                                    .build()
                    ).get(0),
                    org.loomsip.message.header.DialogHeaderValues.routes(
                            SipHeaders.builder().add("Route", "<sip:edge.example.com;lr>").build()
                    ).getFirst()
            )
                    : List.of();
            return manager.create(new DialogSnapshot(
                    new DialogId("call@example.com", "local-tag", "remote-tag"),
                    DialogRole.UAC,
                    DialogState.CONFIRMED,
                    SipUri.parse("sip:alice@example.com"),
                    SipUri.parse("sip:bob@example.com"),
                    10,
                    20,
                    routes,
                    Optional.of(SipUri.parse("sip:bob@server.example.com")),
                    false
            ));
        }

        private DialogHandle createEarlyDialog() {
            return manager.create(new DialogSnapshot(
                    new DialogId("call@example.com", "local-tag", "remote-tag"),
                    DialogRole.UAC,
                    DialogState.EARLY,
                    SipUri.parse("sip:alice@example.com"),
                    SipUri.parse("sip:bob@example.com"),
                    10,
                    20,
                    List.of(),
                    Optional.of(SipUri.parse("sip:bob@server.example.com")),
                    false
            ));
        }

        @Override
        public void close() {
            manager.close();
            scheduler.close();
        }
    }

    private static final class RecordingDispatcher implements DialogRequestDispatcher {

        private final List<SipRequest> invites = new ArrayList<>();
        private final List<SipRequest> nonInvites = new ArrayList<>();
        private final List<ClientTransactionHandle> nonInviteHandles = new ArrayList<>();
        private final List<TransportEndpoint> targets = new ArrayList<>();

        @Override
        public InviteClientHandle sendInvite(SipRequest request, TransportEndpoint target) {
            invites.add(request);
            targets.add(target);
            return new InviteClientHandle() {
                @Override
                public TransactionKey key() {
                    return null;
                }

                @Override
                public SipRequest originalRequest() {
                    return request;
                }

                @Override
                public InviteClientState state() {
                    return InviteClientState.CALLING;
                }

                @Override
                public CompletionStage<Void> terminated() {
                    return CompletableFuture.completedFuture(null);
                }
            };
        }

        @Override
        public ClientTransactionHandle sendNonInvite(SipRequest request, TransportEndpoint target) {
            nonInvites.add(request);
            targets.add(target);
            ClientTransactionHandle handle = new ClientTransactionHandle() {
                @Override
                public TransactionKey key() {
                    return null;
                }

                @Override
                public SipRequest originalRequest() {
                    return request;
                }

                @Override
                public NonInviteClientState state() {
                    return NonInviteClientState.TRYING;
                }

                @Override
                public CompletionStage<Void> terminated() {
                    return CompletableFuture.completedFuture(null);
                }
            };
            nonInviteHandles.add(handle);
            return handle;
        }
    }

    private static final class RecordingServerTransaction implements ServerTransactionHandle {

        private final AtomicReference<SipResponse> response = new AtomicReference<>();

        @Override
        public TransactionKey key() {
            return null;
        }

        @Override
        public NonInviteServerState state() {
            return NonInviteServerState.TRYING;
        }

        @Override
        public void sendResponse(SipResponse response) {
            this.response.set(response);
        }

        @Override
        public CompletionStage<Void> terminated() {
            return CompletableFuture.completedFuture(null);
        }
    }
}
