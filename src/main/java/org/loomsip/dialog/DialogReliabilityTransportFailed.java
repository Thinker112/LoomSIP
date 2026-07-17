package org.loomsip.dialog;

import org.loomsip.message.SipMessage;

import java.util.Objects;

/** Completion event reporting a failed Dialog-owned transport write. */
record DialogReliabilityTransportFailed(
        DialogInviteKey key,
        SipMessage message,
        Throwable cause
) implements DialogEvent {

    DialogReliabilityTransportFailed {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(cause, "cause");
    }
}
