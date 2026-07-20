package org.loomsip.dialog;

import java.util.Objects;

/** Session Timer signal serialized with all other Dialog state changes. */
record DialogSessionTimerSignalled(SessionTimerSignal signal) implements DialogEvent {

    DialogSessionTimerSignalled {
        Objects.requireNonNull(signal, "signal");
    }
}
