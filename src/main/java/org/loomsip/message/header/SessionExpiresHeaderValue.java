package org.loomsip.message.header;

import java.util.Objects;
import java.util.Optional;

/** Typed RFC 4028 Session-Expires interval and optional refresher role. */
public record SessionExpiresHeaderValue(int intervalSeconds, Optional<SessionRefresher> refresher) {

    /** Validates the positive interval and optional refresher. */
    public SessionExpiresHeaderValue {
        if (intervalSeconds <= 0) {
            throw new IllegalArgumentException("Session-Expires interval must be positive");
        }
        refresher = Objects.requireNonNull(refresher, "refresher");
    }

    /** @return canonical Session-Expires wire value */
    public String wireValue() {
        return refresher.map(value -> intervalSeconds + ";refresher=" + value.wireValue())
                .orElseGet(() -> Integer.toString(intervalSeconds));
    }
}
