package org.loomsip.dialog;

/** Immutable scheduler callback delivered to a Dialog Mailbox. */
record DialogTimerExpired(DialogTimerKey key, long generation) implements DialogEvent {
}
