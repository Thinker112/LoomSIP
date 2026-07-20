package org.loomsip.transport;

/** Failure while submitting or completing a transport write. */
public class TransportWriteException extends TransportException {

    /**
     * Creates a write failure.
     *
     * @param message failure description
     * @param cause underlying write failure
     */
    public TransportWriteException(String message, Throwable cause) {
        super(message, cause);
    }
}
