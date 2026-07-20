package org.loomsip.transport;

/** A configured transport resource limit rejected an operation. */
public class TransportLimitException extends TransportException {

    /**
     * Creates a limit failure.
     *
     * @param message limit and operation description
     */
    public TransportLimitException(String message) {
        super(message);
    }
}
