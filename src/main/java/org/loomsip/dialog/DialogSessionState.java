package org.loomsip.dialog;

import java.util.Objects;

/** Immutable current RFC 4028 timer state for one Dialog. */
public record DialogSessionState(
        int intervalSeconds,
        boolean localRefresher,
        SessionRefreshMethod refreshMethod,
        long generation
) {

    /** Validates session interval, method, and timer generation. */
    public DialogSessionState {
        if (intervalSeconds <= 0 || generation <= 0) {
            throw new IllegalArgumentException("invalid Dialog session timer state");
        }
        Objects.requireNonNull(refreshMethod, "refreshMethod");
    }
}
