package org.loomsip.transport;

/**
 * Checked failure to start, operate, or stop a SIP transport.
 */
public class TransportException extends Exception {

    /**
     * Creates a transport failure without an underlying cause.
     *
     * @param message failure description
     */
    public TransportException(String message) {
        super(message);
    }

    /**
     * Creates a transport failure with its underlying cause.
     *
     * @param message failure description
     * @param cause underlying failure
     */
    public TransportException(String message, Throwable cause) {
        super(message, cause);
    }
}
