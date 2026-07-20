package org.loomsip.auth;

/** Safe-to-report infrastructure failure while performing UAS Digest authentication. */
public final class ServerAuthenticationException extends IllegalStateException {

    /**
     * Creates a server authentication infrastructure failure.
     *
     * @param message safe diagnostic without credential, nonce, or response-hash material
     */
    public ServerAuthenticationException(String message) {
        super(message);
    }
}
