package org.loomsip.concurrent;

/**
 * Indicates that an event was submitted after mailbox shutdown began.
 */
public final class MailboxClosedException extends IllegalStateException {

    /**
     * Creates a closed-mailbox rejection.
     *
     * @param state state that rejected the event
     */
    public MailboxClosedException(MailboxState state) {
        super("mailbox does not accept events in state " + state);
    }
}
