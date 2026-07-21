package org.loomsip.subscription;

import org.junit.jupiter.api.Test;
import org.loomsip.message.SipHeaders;
import org.loomsip.message.SipMethod;
import org.loomsip.message.SipRequest;
import org.loomsip.message.SipUri;
import org.loomsip.message.header.EventHeaderValue;
import org.loomsip.message.header.SipHeaderValueException;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SubscriptionDispatcherTest {

    @Test
    void selectsNormalizedRegisteredHandler() throws Exception {
        SubscriptionDispatcher dispatcher = new SubscriptionDispatcher();
        SubscriptionHandler handler = request -> CompletableFuture.completedFuture(
                new SubscriptionAcceptance(202, "Accepted", request.expires().seconds())
        );
        dispatcher.register(new EventHeaderValue("Presence", Optional.empty()), handler);

        SubscriptionDispatcher.Dispatch dispatch = dispatcher.dispatch(subscribe("presence", "120")).orElseThrow();

        assertEquals(handler, dispatch.handler());
        assertEquals("presence", dispatch.request().event().packageName());
        assertEquals(120, dispatch.request().expires().seconds());
        assertEquals(202, dispatch.handler().onSubscribe(dispatch.request()).toCompletableFuture().join().statusCode());
    }

    @Test
    void rejectsDuplicateAndMalformedRequestsAndLeavesUnknownPackageUnmatched() throws Exception {
        SubscriptionDispatcher dispatcher = new SubscriptionDispatcher();
        dispatcher.register(new EventHeaderValue("presence", Optional.empty()), request ->
                CompletableFuture.completedFuture(new SubscriptionAcceptance(200, "OK", 60)));
        assertThrows(IllegalArgumentException.class, () -> dispatcher.register(
                new EventHeaderValue("PRESENCE", Optional.empty()), request -> CompletableFuture.completedFuture(null)
        ));
        assertFalse(dispatcher.dispatch(subscribe("refer", "60")).isPresent());
        assertThrows(IllegalArgumentException.class, () -> dispatcher.dispatch(
                new SipRequest(SipMethod.NOTIFY, SipUri.parse("sip:bob@example.com"), SipHeaders.empty())
        ));
        assertThrows(SipHeaderValueException.class, () -> dispatcher.dispatch(subscribe("presence", "bad")));
    }

    private static SipRequest subscribe(String event, String expires) {
        return new SipRequest(SipMethod.SUBSCRIBE, SipUri.parse("sip:bob@example.com"), SipHeaders.builder()
                .add("Via", "SIP/2.0/UDP client.example.com;branch=z9hG4bK-dispatch")
                .add("From", "<sip:alice@example.com>;tag=local-tag")
                .add("To", "<sip:bob@example.com>")
                .add("Call-ID", "subscription@example.com")
                .add("CSeq", "1 SUBSCRIBE")
                .add("Event", event)
                .add("Expires", expires)
                .build());
    }
}
