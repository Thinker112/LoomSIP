package org.loomsip.dialog;

/** Runtime capacity or consistency failure in a Dialog repository. */
public final class DialogRepositoryException extends RuntimeException {

    /**
     * Creates a repository failure.
     *
     * @param message failure description
     */
    public DialogRepositoryException(String message) {
        super(message);
    }

    /**
     * Creates a repository failure with its cause.
     *
     * @param message failure description
     * @param cause underlying failure
     */
    public DialogRepositoryException(String message, Throwable cause) {
        super(message, cause);
    }
}
