package org.loomsip.transaction;

/**
 * Runtime failure to register or create a transaction repository entry.
 */
public final class TransactionRepositoryException extends RuntimeException {

    /**
     * Creates a repository failure.
     *
     * @param message failure description
     */
    public TransactionRepositoryException(String message) {
        super(message);
    }

    /**
     * Creates a repository failure with its underlying cause.
     *
     * @param message failure description
     * @param cause candidate creation failure
     */
    public TransactionRepositoryException(String message, Throwable cause) {
        super(message, cause);
    }
}
