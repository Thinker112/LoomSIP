package org.loomsip.auth;

/** Outcome after a UAS authentication gate inspects one initial request. */
public enum ServerAuthenticationDisposition {
    /** Request passed Digest verification and replay protection. */
    AUTHENTICATED,
    /** Request did not pass; send the supplied generic 401 challenge. */
    CHALLENGED
}
