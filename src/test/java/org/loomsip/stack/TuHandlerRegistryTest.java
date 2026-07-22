package org.loomsip.stack;

import org.junit.jupiter.api.Test;
import org.loomsip.message.SipHeaders;
import org.loomsip.message.SipMethod;
import org.loomsip.message.SipRequest;
import org.loomsip.message.SipResponse;
import org.loomsip.message.SipResponses;
import org.loomsip.message.SipUri;
import org.loomsip.transaction.TransactionKey;
import org.loomsip.transaction.invite.InviteServerHandle;
import org.loomsip.transaction.invite.InviteServerState;
import org.loomsip.transaction.noninvite.NonInviteServerState;
import org.loomsip.transaction.noninvite.ServerTransactionHandle;
import org.loomsip.transport.TransportContext;
import org.loomsip.transport.TransportProtocol;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TuHandlerRegistryTest {

    @Test
    void routesInviteAndOtherRequestsToTheirRegisteredHandlers() throws Exception {
        AtomicInteger invites = new AtomicInteger();
        AtomicInteger requests = new AtomicInteger();
        TuHandlerRegistry registry = TuHandlerRegistry.builder()
                .inviteHandler(context -> {
                    invites.incrementAndGet();
                    context.respond(SipResponses.createResponse(context.request(), 200, "OK", "uas"));
                })
                .requestHandler(context -> {
                    requests.incrementAndGet();
                    context.respond(SipResponses.createResponse(context.request(), 200, "OK"));
                })
                .build();
        AtomicReference<SipResponse> inviteResponse = new AtomicReference<>();
        AtomicReference<SipResponse> optionsResponse = new AtomicReference<>();

        registry.dispatch(new IncomingRequestContext(request(SipMethod.INVITE), context(), inviteResponse::set), ignored -> { });
        registry.dispatch(new IncomingRequestContext(request(SipMethod.OPTIONS), context(), optionsResponse::set), ignored -> { });

        assertEquals(1, invites.get());
        assertEquals(1, requests.get());
        assertEquals(200, inviteResponse.get().statusCode());
        assertEquals(200, optionsResponse.get().statusCode());
    }

    @Test
    void acceptsProvisionalResponsesButOnlyOneFinalResponse() throws Exception {
        AtomicReference<SipResponse> lastResponse = new AtomicReference<>();
        IncomingRequestContext context = new IncomingRequestContext(
                request(SipMethod.INVITE), context(), lastResponse::set
        );

        assertTrue(context.respond(SipResponses.createResponse(context.request(), 100, "Trying")));
        assertFalse(context.hasResponded());
        assertTrue(context.respond(SipResponses.createResponse(context.request(), 200, "OK", "uas")));
        assertTrue(context.hasResponded());
        assertFalse(context.respond(SipResponses.createResponse(context.request(), 486, "Busy Here", "uas")));
        assertEquals(200, lastResponse.get().statusCode());
    }

    @Test
    void bridgeUsesExistingServerTransactionHandles() throws Exception {
        TuHandlerRegistry registry = TuHandlerRegistry.builder()
                .inviteHandler(context -> context.respond(SipResponses.createResponse(context.request(), 200, "OK", "uas")))
                .requestHandler(context -> context.respond(SipResponses.createResponse(context.request(), 200, "OK")))
                .build();
        TuServerTransactionBridge bridge = new TuServerTransactionBridge(registry, ignored -> { });
        RecordingInviteHandle invite = new RecordingInviteHandle();
        RecordingServerHandle options = new RecordingServerHandle();

        bridge.onInvite(invite, request(SipMethod.INVITE), context());
        bridge.onRequest(options, request(SipMethod.OPTIONS), context());

        assertEquals(200, invite.response.get().statusCode());
        assertEquals(200, options.response.get().statusCode());
    }

    private static SipRequest request(SipMethod method) {
        return new SipRequest(method, SipUri.parse("sip:service@example.com"), SipHeaders.builder()
                .add("Via", "SIP/2.0/UDP client.example.com;branch=z9hG4bK-stack")
                .add("From", "<sip:client@example.com>;tag=caller")
                .add("To", "<sip:service@example.com>")
                .add("Call-ID", "stack-test@example.com")
                .add("CSeq", "1 " + method.value())
                .build());
    }

    private static TransportContext context() throws Exception {
        return new TransportContext(TransportProtocol.UDP,
                new InetSocketAddress(InetAddress.getLoopbackAddress(), 5060),
                new InetSocketAddress(InetAddress.getLoopbackAddress(), 5061));
    }

    private static final class RecordingInviteHandle implements InviteServerHandle {
        private final AtomicReference<SipResponse> response = new AtomicReference<>();
        @Override public TransactionKey key() { return null; }
        @Override public InviteServerState state() { return InviteServerState.PROCEEDING; }
        @Override public void sendResponse(SipResponse value) { response.set(value); }
        @Override public java.util.concurrent.CompletionStage<Void> terminated() { return CompletableFuture.completedFuture(null); }
    }

    private static final class RecordingServerHandle implements ServerTransactionHandle {
        private final AtomicReference<SipResponse> response = new AtomicReference<>();
        @Override public TransactionKey key() { return null; }
        @Override public NonInviteServerState state() { return NonInviteServerState.TRYING; }
        @Override public void sendResponse(SipResponse value) { response.set(value); }
        @Override public java.util.concurrent.CompletionStage<Void> terminated() { return CompletableFuture.completedFuture(null); }
    }
}
