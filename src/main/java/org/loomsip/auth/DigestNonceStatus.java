package org.loomsip.auth;

/** Validation status for a server-issued Digest nonce. */
public enum DigestNonceStatus {
    /** Nonce is active and bound to the presented realm and algorithm. */
    VALID,
    /** Nonce was structurally known but its lifetime has elapsed. */
    STALE,
    /** Nonce is unknown or belongs to another realm or algorithm. */
    UNKNOWN,
    /** qop nonce-count was repeated or decreased for the same client nonce. */
    REPLAYED
}
