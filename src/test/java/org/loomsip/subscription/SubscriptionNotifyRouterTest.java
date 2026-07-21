package org.loomsip.subscription;

import org.junit.jupiter.api.Test;
import org.loomsip.message.SipHeaders;
import org.loomsip.message.SipMethod;
import org.loomsip.message.SipRequest;
import org.loomsip.message.SipUri;
import org.loomsip.message.header.EventHeaderValue;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SubscriptionNotifyRouterTest {

    @Test
    void routesActiveAndTerminatedNotifyToMatchingSubscription() {
        SubscriptionManager manager = manager();
        SubscriptionId id = id();
        manager.create(id);
        SubscriptionNotifyRouter router = new SubscriptionNotifyRouter(manager);

        SubscriptionNotifyResult active = router.route(notify("active")).toCompletableFuture().join();
        assertEquals(200, active.response().statusCode());
        assertEquals(SubscriptionLifecycleState.ACTIVE, active.subscription().orElseThrow().state());

        SubscriptionNotifyResult terminated = router.route(notify("terminated;reason=timeout"))
                .toCompletableFuture().join();
        assertEquals(200, terminated.response().statusCode());
        assertEquals(SubscriptionLifecycleState.TERMINATED, terminated.subscription().orElseThrow().state());
        assertTrue(manager.find(id).isEmpty());
    }

    @Test
    void rejectsUnknownAndMalformedNotifyWithoutCreatingSubscription() {
        SubscriptionNotifyRouter router = new SubscriptionNotifyRouter(manager());

        assertEquals(481, router.route(notify("active")).toCompletableFuture().join().response().statusCode());
        SipRequest malformed = new SipRequest(SipMethod.NOTIFY, SipUri.parse("sip:alice@example.com"), SipHeaders.builder()
                .add("Via", "SIP/2.0/UDP notifier.example.com;branch=z9hG4bK-malformed")
                .add("From", "<sip:bob@example.com>;tag=remote-tag")
                .add("To", "<sip:alice@example.com>;tag=local-tag")
                .add("Call-ID", "subscription@example.com")
                .add("CSeq", "1 NOTIFY")
                .add("Subscription-State", "active")
                .build());
        assertEquals(400, router.route(malformed).toCompletableFuture().join().response().statusCode());
    }

    private static SubscriptionManager manager() {
        return new SubscriptionManager(SubscriptionConfig.DEFAULT, Runnable::run, failure -> {
            throw new AssertionError(failure);
        });
    }

    private static SubscriptionId id() {
        return new SubscriptionId("subscription@example.com", "local-tag", "remote-tag",
                new EventHeaderValue("presence", Optional.empty()));
    }

    private static SipRequest notify(String state) {
        return new SipRequest(SipMethod.NOTIFY, SipUri.parse("sip:alice@example.com"), SipHeaders.builder()
                .add("Via", "SIP/2.0/UDP notifier.example.com;branch=z9hG4bK-notify")
                .add("From", "<sip:bob@example.com>;tag=remote-tag")
                .add("To", "<sip:alice@example.com>;tag=local-tag")
                .add("Call-ID", "subscription@example.com")
                .add("CSeq", "1 NOTIFY")
                .add("Event", "presence")
                .add("Subscription-State", state)
                .build());
    }
}
