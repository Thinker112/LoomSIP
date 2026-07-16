package org.loomsip.concurrent;

/**
 * Lifecycle of a serial mailbox.
 */
public enum MailboxState {
    /** Accepting and processing events. */
    OPEN,
    /** Rejecting new events while previously accepted events drain. */
    CLOSING,
    /** No longer accepting or processing events. */
    CLOSED
}
