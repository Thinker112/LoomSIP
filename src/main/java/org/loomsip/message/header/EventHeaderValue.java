package org.loomsip.message.header;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/** Typed RFC 3265 Event package and optional event-id parameter. */
public record EventHeaderValue(String packageName, Optional<String> eventId) {

    /** Validates the package and optional event-id SIP tokens. */
    public EventHeaderValue {
        packageName = HeaderSyntax.requireToken(Objects.requireNonNull(packageName, "packageName"), "Event package");
        eventId = Objects.requireNonNull(eventId, "eventId").map(value ->
                HeaderSyntax.requireToken(value, "Event id")
        );
    }

    /** @return lower-case package comparison key */
    public String normalizedPackageName() {
        return packageName.toLowerCase(Locale.ROOT);
    }

    /** @return RFC 3265 Event wire value */
    public String wireValue() {
        return eventId.map(value -> packageName + ";id=" + value).orElse(packageName);
    }
}
