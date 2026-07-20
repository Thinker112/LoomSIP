package org.loomsip.auth;

/** Base failure for safe-to-report client Digest authentication errors. */
public class DigestAuthenticationException extends IllegalStateException {

    /**
     * Creates an authentication failure without a cause.
     *
     * @param message safe diagnostic message without credentials or response hashes
     */
    public DigestAuthenticationException(String message) {
        super(message);
    }

    /**
     * Creates an authentication failure with a cause.
     *
     * @param message safe diagnostic message without credentials or response hashes
     * @param cause underlying failure
     */
    public DigestAuthenticationException(String message, Throwable cause) {
        super(message, cause);
    }
}
