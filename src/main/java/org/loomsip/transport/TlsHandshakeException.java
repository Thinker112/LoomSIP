package org.loomsip.transport;

/** Failure while negotiating a TLS session before SIP bytes are accepted. */
public class TlsHandshakeException extends TransportException {

    /**
     * Creates a TLS handshake failure without an underlying cause.
     *
     * @param message failure description
     */
    public TlsHandshakeException(String message) {
        super(message);
    }

    /**
     * Creates a TLS handshake failure with its underlying cause.
     *
     * @param message failure description
     * @param cause underlying TLS failure
     */
    public TlsHandshakeException(String message, Throwable cause) {
        super(message, cause);
    }
}
