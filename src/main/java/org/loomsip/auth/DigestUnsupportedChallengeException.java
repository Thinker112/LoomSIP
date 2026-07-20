package org.loomsip.auth;

/** Indicates a syntactically valid Digest challenge outside the supported policy. */
public final class DigestUnsupportedChallengeException extends DigestAuthenticationException {

    /**
     * Creates an unsupported-challenge failure.
     *
     * @param message safe diagnostic describing the unsupported feature
     */
    public DigestUnsupportedChallengeException(String message) {
        super(message);
    }
}
