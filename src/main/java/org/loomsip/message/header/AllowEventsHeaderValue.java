package org.loomsip.message.header;

import org.loomsip.message.SipHeaders;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

/** Ordered RFC 3265 Allow-Events capability list. */
public record AllowEventsHeaderValue(List<EventHeaderValue> events) {

    /** Validates that each advertised event has no event-id and is unique by package. */
    public AllowEventsHeaderValue {
        events = List.copyOf(Objects.requireNonNull(events, "events"));
        if (events.isEmpty()) {
            throw new IllegalArgumentException("Allow-Events must not be empty");
        }
        LinkedHashSet<String> names = new LinkedHashSet<>();
        for (EventHeaderValue event : events) {
            if (event.eventId().isPresent() || !names.add(event.normalizedPackageName())) {
                throw new IllegalArgumentException("Allow-Events contains an invalid or duplicate event package");
            }
        }
    }

    /** @return canonical comma-separated capability value */
    public String wireValue() {
        return events.stream().map(EventHeaderValue::packageName).collect(java.util.stream.Collectors.joining(", "));
    }

    /** Applies this capability list as one Allow-Events header. */
    public SipHeaders applyTo(SipHeaders headers) {
        return Objects.requireNonNull(headers, "headers").withReplaced("Allow-Events", wireValue());
    }
}
