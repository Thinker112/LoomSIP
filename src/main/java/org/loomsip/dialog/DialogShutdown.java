package org.loomsip.dialog;

import java.util.Objects;

/** Internal command requesting deterministic Dialog shutdown. */
record DialogShutdown(DialogTerminationReason reason) implements DialogEvent {

    DialogShutdown {
        Objects.requireNonNull(reason, "reason");
    }
}
