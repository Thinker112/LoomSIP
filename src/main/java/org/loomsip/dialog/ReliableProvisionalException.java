package org.loomsip.dialog;

/** Safe protocol failure while managing RFC 3262 reliable provisional responses. */
public final class ReliableProvisionalException extends IllegalStateException {

    /**
     * Creates a reliable provisional protocol failure.
     *
     * @param message safe diagnostic without complete SIP messages
     */
    public ReliableProvisionalException(String message) {
        super(message);
    }
}
