package org.loomsip.transport;

/** Failure while establishing a reliable transport connection. */
public class TransportConnectException extends TransportException {

    /**
     * Creates a connection-establishment failure.
     *
     * @param message failure description
     * @param cause underlying connection failure
     */
    public TransportConnectException(String message, Throwable cause) {
        super(message, cause);
    }
}
