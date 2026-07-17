package org.loomsip.dialog;

import java.util.Objects;

/** One named timer associated with a specific Dialog INVITE exchange. */
record DialogTimerKey(DialogInviteKey invite, DialogTimer timer) {

    DialogTimerKey {
        Objects.requireNonNull(invite, "invite");
        Objects.requireNonNull(timer, "timer");
    }
}
