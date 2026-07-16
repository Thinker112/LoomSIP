package org.loomsip.transaction;

/**
 * Checked failure to derive a transaction identity from SIP message fields.
 */
public final class TransactionKeyException extends Exception {

    /**
     * Creates a transaction-key derivation failure.
     *
     * @param message failure description
     */
    public TransactionKeyException(String message) {
        super(message);
    }

    /**
     * Creates a transaction-key derivation failure with its underlying cause.
     *
     * @param message failure description
     * @param cause typed-header parsing or validation failure
     */
    public TransactionKeyException(String message, Throwable cause) {
        super(message, cause);
    }
}
