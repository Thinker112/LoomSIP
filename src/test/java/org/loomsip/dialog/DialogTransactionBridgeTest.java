package org.loomsip.dialog;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.loomsip.message.SipHeaders;
import org.loomsip.message.SipMessage;
import org.loomsip.message.SipMethod;
import org.loomsip.message.SipRequest;
import org.loomsip.message.SipResponse;
import org.loomsip.message.SipResponses;
import org.loomsip.message.SipUri;
import org.loomsip.transaction.TransactionMessageSender;
import org.loomsip.transaction.invite.InviteClientHandle;
import org.loomsip.transaction.invite.InviteClientListener;
import org.loomsip.transaction.invite.InviteServerHandle;
import org.loomsip.transaction.invite.InviteServerListener;
import org.loomsip.transaction.invite.InviteTransactionConfig;
import org.loomsip.transaction.invite.InviteTransactionManager;
import org.loomsip.transaction.timer.SipTimerConfig;
import org.loomsip.transaction.timer.VirtualSipScheduler;
import org.loomsip.transport.InboundSipMessage;
import org.loomsip.transport.SendResult;
import org.loomsip.transport.TransportContext;
import org.loomsip.transport.TransportEndpoint;
import org.loomsip.transport.TransportProtocol;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Timeout(10)
class DialogTransactionBridgeTest {

    private static final SipTimerConfig TIMERS = SipTimerConfig.DEFAULT;

    @Test
    void uacCreatesForksConfirmsSuccessesAndTimerMCleansOnlyEarlyDialog() throws Exception {
        try (ClientRig rig = new ClientRig(invite(true))) {
            InviteClientHandle transaction = rig.start();

            rig.deliver(response(rig.invite, 180, "Ringing", "fork-a",
                    "sip:bob@early-a.example.com", true));
            rig.deliver(response(rig.invite, 183, "Progress", "fork-b", null, false));
            rig.deliver(response(rig.invite, 200, "OK", "fork-a",
                    "sip:bob@confirmed-a.example.com", true));
            rig.deliver(response(rig.invite, 200, "OK", "fork-c",
                    "sip:bob@confirmed-c.example.com", false));

            DialogHandle forkA = rig.dialog("fork-a");
            DialogHandle forkB = rig.dialog("fork-b");
            DialogHandle forkC = rig.dialog("fork-c");
            assertEquals(DialogState.CONFIRMED, forkA.snapshot().state());
            assertEquals(DialogState.EARLY, forkB.snapshot().state());
            assertEquals(DialogState.CONFIRMED, forkC.snapshot().state());
            assertEquals(SipUri.parse("sip:bob@confirmed-a.example.com"),
                    forkA.snapshot().remoteTarget().orElseThrow());
            assertEquals(List.of("sip:proxy-2.example.com;lr", "sip:proxy-1.example.com;lr"),
                    forkA.snapshot().routeSet().stream().map(value -> value.uri().value()).toList());
            assertEquals(List.of(
                    "180:EARLY",
                    "183:EARLY",
                    "200:CONFIRMED",
                    "200:CONFIRMED"
            ), rig.application.observedResponses);

            rig.scheduler.advanceBy(TIMERS.sixtyFourT1());

            assertEquals(org.loomsip.transaction.invite.InviteClientState.TERMINATED,
                    transaction.state());
            assertFalse(rig.dialogs.find(forkB.id()).isPresent());
            assertEquals(List.of("fork-a", "fork-c"), rig.dialogs.findBySet(forkA.id().setId())
                    .stream().map(handle -> handle.id().remoteTag()).toList());
        }
    }

    @Test
    void nonSuccessFinalResponseCleansEarlySetBeforeApplicationCallback() throws Exception {
        try (ClientRig rig = new ClientRig(invite(true))) {
            rig.start();
            rig.deliver(response(rig.invite, 180, "Ringing", "failed-fork",
                    "sip:bob@early.example.com", false));
            rig.deliver(response(rig.invite, 486, "Busy Here", "failed-fork", null, false));

            assertEquals(List.of(1, 0), rig.application.activeDialogCounts);
            assertEquals(0, rig.dialogs.activeDialogs());
            assertTrue(rig.application.errors.isEmpty());
        }
    }

    @Test
    void malformedUacSuccessIsReportedButStillDeliveredToApplication() throws Exception {
        try (ClientRig rig = new ClientRig(invite(true))) {
            rig.start();
            rig.deliver(response(rig.invite, 200, "OK", "missing-contact", null, false));

            assertEquals(List.of(200), rig.application.statuses);
            assertEquals(0, rig.dialogs.activeDialogs());
            assertEquals(1, rig.application.errors.size());
            assertInstanceOf(DialogBridgeException.class, rig.application.errors.getFirst());
        }
    }

    @Test
    void uasRegistersEarlyAndConfirmedDialogBeforeSendingResponses() throws Exception {
        try (ServerRig rig = new ServerRig(invite(true))) {
            rig.receiveInvite();
            assertNotNull(rig.application.transaction);

            rig.sender.beforeSend = message -> {
                SipResponse response = (SipResponse) message;
                DialogState expected = response.statusCode() < 200
                        ? DialogState.EARLY
                        : DialogState.CONFIRMED;
                assertEquals(expected, rig.dialog("server-tag").snapshot().state());
            };
            rig.application.transaction.sendResponse(response(
                    rig.invite,
                    180,
                    "Ringing",
                    "server-tag",
                    "sip:service@server.example.com",
                    false
            ));
            rig.application.transaction.sendResponse(response(
                    rig.invite,
                    200,
                    "OK",
                    "server-tag",
                    "sip:service@server.example.com",
                    false
            ));

            DialogSnapshot snapshot = rig.dialog("server-tag").snapshot();
            assertEquals(DialogRole.UAS, snapshot.role());
            assertEquals(DialogState.CONFIRMED, snapshot.state());
            assertEquals(SipUri.parse("sip:alice@client.example.com"),
                    snapshot.remoteTarget().orElseThrow());
            assertEquals(1, snapshot.remoteCSeq());
            assertEquals(0, snapshot.localCSeq());
            assertEquals(List.of("sip:proxy-1.example.com;lr", "sip:proxy-2.example.com;lr"),
                    snapshot.routeSet().stream().map(value -> value.uri().value()).toList());
            assertEquals(List.of(180, 200), rig.sender.responses().stream()
                    .map(SipResponse::statusCode).toList());
            assertTrue(rig.application.errors.isEmpty());

            rig.scheduler.advanceBy(TIMERS.sixtyFourT1());
            assertEquals(DialogState.CONFIRMED, rig.dialog("server-tag").snapshot().state());
            assertEquals(0, rig.transactions.activeServerTransactions());
        }
    }

    @Test
    void uasNonSuccessResponseRemovesEarlyDialogBeforeTransportSend() throws Exception {
        try (ServerRig rig = new ServerRig(invite(true))) {
            rig.receiveInvite();
            rig.application.transaction.sendResponse(response(
                    rig.invite, 180, "Ringing", "server-tag",
                    "sip:service@server.example.com", false
            ));
            rig.sender.beforeSend = message -> {
                if (((SipResponse) message).statusCode() >= 300) {
                    assertEquals(0, rig.dialogs.activeDialogs());
                }
            };

            rig.application.transaction.sendResponse(response(
                    rig.invite, 486, "Busy Here", "server-tag", null, false
            ));

            assertEquals(0, rig.dialogs.activeDialogs());
            assertEquals(List.of(180, 486), rig.sender.responses().stream()
                    .map(SipResponse::statusCode).toList());
        }
    }

    @Test
    void uasRejectsDialogFormingSuccessWithoutRequiredContacts() throws Exception {
        try (ServerRig rig = new ServerRig(invite(false))) {
            rig.receiveInvite();
            rig.application.transaction.sendResponse(response(
                    rig.invite, 200, "OK", "server-tag", null, false
            ));

            assertTrue(rig.sender.responses().isEmpty());
            assertEquals(0, rig.dialogs.activeDialogs());
            assertEquals(1, rig.application.errors.size());
            assertInstanceOf(DialogBridgeException.class, rig.application.errors.getFirst());
        }
    }

    @Test
    void tryingAndUntaggedProvisionalDoNotCreateDialog() throws Exception {
        try (ClientRig rig = new ClientRig(invite(true))) {
            rig.start();
            rig.deliver(response(rig.invite, 100, "Trying", null, null, false));
            rig.deliver(response(rig.invite, 180, "Ringing", null, null, false));

            assertEquals(0, rig.dialogs.activeDialogs());
            assertEquals(List.of(100, 180), rig.application.statuses);
        }
    }

    private static SipRequest invite(boolean includeContact) {
        SipHeaders.Builder headers = SipHeaders.builder()
                .add("Via", "SIP/2.0/UDP client.example.com:5060;branch=z9hG4bK-dialog-bridge;rport")
                .add("Max-Forwards", "70")
                .add("From", "<sip:alice@example.com>;tag=client-tag")
                .add("To", "<sip:bob@example.com>")
                .add("Call-ID", "dialog-bridge@example.com")
                .add("CSeq", "1 INVITE")
                .add("Record-Route", "<sip:proxy-1.example.com;lr>, <sip:proxy-2.example.com;lr>");
        if (includeContact) {
            headers.add("Contact", "<sip:alice@client.example.com>");
        }
        return new SipRequest(
                SipMethod.INVITE,
                SipUri.parse("sip:bob@example.com"),
                headers.build()
        );
    }

    private static SipResponse response(
            SipRequest invite,
            int status,
            String reason,
            String toTag,
            String contact,
            boolean includeRecordRoute
    ) {
        SipResponse base = toTag == null
                ? SipResponses.createResponse(invite, status, reason)
                : SipResponses.createResponse(invite, status, reason, toTag);
        SipHeaders.Builder headers = base.headers().toBuilder();
        if (contact != null) {
            headers.add("Contact", '<' + contact + '>');
        }
        if (includeRecordRoute) {
            headers.add("Record-Route", "<sip:proxy-1.example.com;lr>, <sip:proxy-2.example.com;lr>");
        }
        return new SipResponse(base.version(), status, reason, headers.build(), base.body());
    }

    private static TransportEndpoint endpoint(int port) throws Exception {
        return TransportEndpoint.udp(new InetSocketAddress(InetAddress.getLoopbackAddress(), port));
    }

    private static DialogManager dialogManager() {
        return new DialogManager(
                new DialogConfig(32, 64, 32),
                new DialogLifecycleListener() {
                },
                new InMemoryDialogRepository(32),
                Runnable::run,
                Runnable::run
        );
    }

    private static final class ClientRig implements AutoCloseable {

        private final SipRequest invite;
        private final VirtualSipScheduler scheduler = new VirtualSipScheduler();
        private final DialogManager dialogs = dialogManager();
        private final RecordingSender sender = new RecordingSender();
        private final RecordingClientListener application = new RecordingClientListener(dialogs);
        private final DialogTransactionBridge bridge;
        private final InviteTransactionManager transactions;
        private final TransportEndpoint clientEndpoint;
        private final TransportEndpoint serverEndpoint;

        private ClientRig(SipRequest invite) throws Exception {
            this.invite = invite;
            clientEndpoint = endpoint(19060);
            serverEndpoint = endpoint(19061);
            bridge = new DialogTransactionBridge(
                    dialogs,
                    application,
                    (transaction, request, context) -> {
                    }
            );
            transactions = new InviteTransactionManager(
                    sender,
                    TIMERS,
                    InviteTransactionConfig.DEFAULT,
                    bridge.clientListener(),
                    bridge.serverListener(),
                    scheduler,
                    Runnable::run,
                    Runnable::run
            );
        }

        private InviteClientHandle start() throws Exception {
            return transactions.sendInvite(invite, serverEndpoint);
        }

        private void deliver(SipResponse response) {
            transactions.onMessage(new InboundSipMessage(
                    response,
                    new TransportContext(
                            TransportProtocol.UDP,
                            clientEndpoint.address(),
                            serverEndpoint.address()
                    )
            ));
        }

        private DialogHandle dialog(String remoteTag) {
            return dialogs.find(new DialogId(
                    "dialog-bridge@example.com",
                    "client-tag",
                    remoteTag
            )).orElseThrow();
        }

        @Override
        public void close() {
            transactions.close();
            dialogs.close();
            scheduler.close();
        }
    }

    private static final class ServerRig implements AutoCloseable {

        private final SipRequest invite;
        private final VirtualSipScheduler scheduler = new VirtualSipScheduler();
        private final DialogManager dialogs = dialogManager();
        private final RecordingSender sender = new RecordingSender();
        private final RecordingServerListener application = new RecordingServerListener();
        private final DialogTransactionBridge bridge;
        private final InviteTransactionManager transactions;
        private final TransportEndpoint serverEndpoint;
        private final TransportEndpoint clientEndpoint;

        private ServerRig(SipRequest invite) throws Exception {
            this.invite = invite;
            serverEndpoint = endpoint(19160);
            clientEndpoint = endpoint(19161);
            bridge = new DialogTransactionBridge(
                    dialogs,
                    (transaction, response, context) -> {
                    },
                    application
            );
            transactions = new InviteTransactionManager(
                    sender,
                    TIMERS,
                    InviteTransactionConfig.DEFAULT,
                    bridge.clientListener(),
                    bridge.serverListener(),
                    scheduler,
                    Runnable::run,
                    Runnable::run
            );
        }

        private void receiveInvite() {
            transactions.onMessage(new InboundSipMessage(
                    invite,
                    new TransportContext(
                            TransportProtocol.UDP,
                            serverEndpoint.address(),
                            clientEndpoint.address()
                    )
            ));
        }

        private DialogHandle dialog(String localTag) {
            return dialogs.find(new DialogId(
                    "dialog-bridge@example.com",
                    localTag,
                    "client-tag"
            )).orElseThrow();
        }

        @Override
        public void close() {
            transactions.close();
            dialogs.close();
            scheduler.close();
        }
    }

    private static final class RecordingClientListener implements InviteClientListener {

        private final DialogManager dialogs;
        private final List<String> observedResponses = new ArrayList<>();
        private final List<Integer> statuses = new ArrayList<>();
        private final List<Integer> activeDialogCounts = new ArrayList<>();
        private final List<Throwable> errors = new ArrayList<>();

        private RecordingClientListener(DialogManager dialogs) {
            this.dialogs = dialogs;
        }

        @Override
        public void onResponse(
                InviteClientHandle transaction,
                SipResponse response,
                TransportContext context
        ) {
            statuses.add(response.statusCode());
            activeDialogCounts.add(dialogs.activeDialogs());
            try {
                Optional<String> remoteTag = org.loomsip.message.header.SipHeaderValues.toTag(
                        response.headers()
                );
                if (remoteTag.isPresent() && response.statusCode() < 300) {
                    DialogId id = new DialogId(
                            "dialog-bridge@example.com",
                            "client-tag",
                            remoteTag.orElseThrow()
                    );
                    dialogs.find(id).ifPresent(dialog -> observedResponses.add(
                            response.statusCode() + ":" + dialog.snapshot().state()
                    ));
                }
            } catch (Exception exception) {
                throw new AssertionError(exception);
            }
        }

        @Override
        public void onLayerError(Throwable cause) {
            errors.add(cause);
        }
    }

    private static final class RecordingServerListener implements InviteServerListener {

        private final List<Throwable> errors = new ArrayList<>();
        private InviteServerHandle transaction;

        @Override
        public void onInvite(
                InviteServerHandle transaction,
                SipRequest request,
                TransportContext context
        ) {
            this.transaction = transaction;
        }

        @Override
        public void onLayerError(Throwable cause) {
            errors.add(cause);
        }
    }

    private static final class RecordingSender implements TransactionMessageSender {

        private final List<SipMessage> messages = new ArrayList<>();
        private Consumer<SipMessage> beforeSend = ignored -> {
        };

        @Override
        public CompletionStage<SendResult> send(SipMessage message, TransportEndpoint target) {
            beforeSend.accept(message);
            messages.add(message);
            return CompletableFuture.completedFuture(new SendResult(target, target, 1));
        }

        private List<SipResponse> responses() {
            return messages.stream()
                    .filter(SipResponse.class::isInstance)
                    .map(SipResponse.class::cast)
                    .toList();
        }
    }
}
