package org.loomsip.auth;

/** Result of cryptographically verifying one syntactically valid Digest authorization. */
public enum DigestVerificationResult {
    /** The credential record and authorization response match. */
    VALID,
    /** The authorization is syntactically valid but does not match the request or credential. */
    INVALID
}
