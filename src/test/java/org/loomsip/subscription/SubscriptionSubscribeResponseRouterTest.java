package org.loomsip.subscription;

import org.junit.jupiter.api.Test;
import org.loomsip.message.SipHeaders;
import org.loomsip.message.SipMethod;
import org.loomsip.message.SipRequest;
import org.loomsip.message.SipResponse;
import org.loomsip.message.SipResponses;
import org.loomsip.message.SipUri;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SubscriptionSubscribeResponseRouterTest {

    @Test
    void createsPendingSubscriptionOnlyForSuccessfulResponse() {
        SubscriptionManager manager = manager();
        SubscriptionSubscribeResponseRouter router = new SubscriptionSubscribeResponseRouter(manager);
        SipRequest request = subscribe();
        SipResponse success = SipResponses.createResponse(request, 202, "Accepted", "remote-tag");

        SubscriptionHandle handle = router.route(request, success).toCompletableFuture().join().orElseThrow();
        assertEquals(SubscriptionLifecycleState.PENDING, handle.snapshot().state());
        assertEquals(1, manager.size());
        assertTrue(router.route(request, SipResponses.createResponse(request, 404, "Not Found", "remote-tag"))
                .toCompletableFuture().join().isEmpty());
        assertEquals(1, manager.size());
    }

    @Test
    void rejectsSuccessfulResponseWithoutRemoteTag() {
        SubscriptionSubscribeResponseRouter router = new SubscriptionSubscribeResponseRouter(manager());
        SipRequest request = subscribe();
        SipResponse malformed = SipResponses.createResponse(request, 200, "OK");

        assertTrue(router.route(request, malformed).toCompletableFuture().isCompletedExceptionally());
    }

    private static SubscriptionManager manager() {
        return new SubscriptionManager(SubscriptionConfig.DEFAULT, Runnable::run, failure -> {
            throw new AssertionError(failure);
        });
    }

    private static SipRequest subscribe() {
        return new SipRequest(SipMethod.SUBSCRIBE, SipUri.parse("sip:bob@example.com"), SipHeaders.builder()
                .add("Via", "SIP/2.0/UDP client.example.com;branch=z9hG4bK-subscribe")
                .add("From", "<sip:alice@example.com>;tag=local-tag")
                .add("To", "<sip:bob@example.com>")
                .add("Call-ID", "subscription@example.com")
                .add("CSeq", "1 SUBSCRIBE")
                .add("Event", "presence")
                .add("Expires", "3600")
                .build());
    }
}
