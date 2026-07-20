package org.loomsip.dialog;

import java.util.Objects;

/** Immutable generation-bearing signal emitted to the owning Dialog Mailbox. */
public record SessionTimerSignal(SessionTimerAction action, DialogSessionState state) {

    /** Validates signal components. */
    public SessionTimerSignal {
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(state, "state");
    }
}
