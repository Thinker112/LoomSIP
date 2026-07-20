package org.loomsip.transport;

/** A reliable transport connection closed before an operation completed. */
public class TransportConnectionClosedException extends TransportException {

    /**
     * Creates a connection-closed failure.
     *
     * @param message failure description
     */
    public TransportConnectionClosedException(String message) {
        super(message);
    }
}
