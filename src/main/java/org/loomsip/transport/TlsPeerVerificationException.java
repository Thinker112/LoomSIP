package org.loomsip.transport;

/** TLS failure caused by an untrusted, expired, or identity-mismatched peer. */
public class TlsPeerVerificationException extends TlsHandshakeException {

    /**
     * Creates a peer-verification failure without an underlying cause.
     *
     * @param message failure description
     */
    public TlsPeerVerificationException(String message) {
        super(message);
    }

    /**
     * Creates a peer-verification failure with its underlying cause.
     *
     * @param message failure description
     * @param cause underlying certificate or identity failure
     */
    public TlsPeerVerificationException(String message, Throwable cause) {
        super(message, cause);
    }
}
