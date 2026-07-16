package org.loomsip.concurrent;

/**
 * Indicates that a mailbox reached its configured pending-event limit.
 */
public final class MailboxFullException extends IllegalStateException {

    /**
     * Creates a capacity rejection.
     *
     * @param capacity configured pending-event capacity
     */
    public MailboxFullException(int capacity) {
        super("mailbox pending-event capacity reached: " + capacity);
    }
}
