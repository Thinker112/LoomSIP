package org.loomsip.transaction.noninvite;

/**
 * Runtime routing or lifecycle failure in the Non-INVITE transaction layer.
 */
public final class NonInviteTransactionLayerException extends RuntimeException {

    /**
     * Creates a layer failure.
     *
     * @param message failure description
     */
    public NonInviteTransactionLayerException(String message) {
        super(message);
    }

    /**
     * Creates a layer failure with its underlying cause.
     *
     * @param message failure description
     * @param cause routing or infrastructure failure
     */
    public NonInviteTransactionLayerException(String message, Throwable cause) {
        super(message, cause);
    }
}
