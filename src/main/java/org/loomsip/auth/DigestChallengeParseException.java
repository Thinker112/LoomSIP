package org.loomsip.auth;

/** Indicates malformed Digest challenge syntax. */
public final class DigestChallengeParseException extends DigestAuthenticationException {

    /**
     * Creates a malformed-challenge failure.
     *
     * @param message safe parse diagnostic
     */
    public DigestChallengeParseException(String message) {
        super(message);
    }
}
