package org.loomsip.transaction.invite;

/** Runtime routing or lifecycle failure in the INVITE transaction layer. */
public final class InviteTransactionLayerException extends RuntimeException {

    /**
     * Creates a layer failure.
     *
     * @param message failure description
     */
    public InviteTransactionLayerException(String message) {
        super(message);
    }

    /**
     * Creates a layer failure with its underlying cause.
     *
     * @param message failure description
     * @param cause routing or infrastructure failure
     */
    public InviteTransactionLayerException(String message, Throwable cause) {
        super(message, cause);
    }
}
