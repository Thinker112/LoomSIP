package org.loomsip.message.header;

import org.junit.jupiter.api.Test;
import org.loomsip.message.SipHeaders;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SubscriptionHeaderValuesTest {

    @Test
    void parsesAndRendersSubscriptionHeaders() throws Exception {
        SipHeaders headers = SipHeaders.builder()
                .add("Event", "presence;id=watcher-1")
                .add("Allow-Events", "presence, refer")
                .add("Allow-Events", "message-summary")
                .add("Expires", "3600")
                .add("Subscription-State", "terminated;reason=timeout;retry-after=60")
                .build();

        EventHeaderValue event = SipHeaderValues.event(headers);
        assertEquals("presence", event.packageName());
        assertEquals("watcher-1", event.eventId().orElseThrow());
        assertEquals("presence;id=watcher-1", event.wireValue());
        assertEquals(List.of("presence", "refer", "message-summary"),
                SipHeaderValues.allowEvents(headers).events().stream().map(EventHeaderValue::packageName).toList());
        assertEquals("presence, refer, message-summary", SipHeaderValues.allowEvents(headers).wireValue());
        assertEquals("3600", SipHeaderValues.expires(headers).wireValue());
        assertEquals(new SubscriptionStateHeaderValue(
                SubscriptionState.TERMINATED,
                Optional.of("timeout"),
                Optional.empty(),
                Optional.of(60)
        ), SipHeaderValues.subscriptionState(headers));
    }

    @Test
    void rejectsMalformedDuplicateAndInvalidStateParameters() {
        assertThrows(SipHeaderValueException.class,
                () -> SipHeaderValues.event(SipHeaders.builder().add("Event", "presence;id=").build()));
        assertThrows(SipHeaderValueException.class,
                () -> SipHeaderValues.event(SipHeaders.builder().add("Event", "presence;id=a;id=b").build()));
        assertThrows(SipHeaderValueException.class,
                () -> SipHeaderValues.allowEvents(SipHeaders.builder().add("Allow-Events", "presence, PRESENCE").build()));
        assertThrows(SipHeaderValueException.class,
                () -> SipHeaderValues.expires(SipHeaders.builder().add("Expires", "-1").build()));
        assertThrows(SipHeaderValueException.class,
                () -> SipHeaderValues.subscriptionState(SipHeaders.builder()
                        .add("Subscription-State", "active;reason=deactivated")
                        .build()));
        assertThrows(SipHeaderValueException.class,
                () -> SipHeaderValues.subscriptionState(SipHeaders.builder()
                        .add("Subscription-State", "terminated;expires=bad")
                        .build()));
    }
}
