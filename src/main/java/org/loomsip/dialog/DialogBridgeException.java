package org.loomsip.dialog;

/** Failure while translating ordered INVITE transaction events into Dialog state. */
public final class DialogBridgeException extends RuntimeException {

    /**
     * Creates a bridge failure with its cause.
     *
     * @param message failure description
     * @param cause underlying parsing, validation, or lifecycle failure
     */
    public DialogBridgeException(String message, Throwable cause) {
        super(message, cause);
    }
}
