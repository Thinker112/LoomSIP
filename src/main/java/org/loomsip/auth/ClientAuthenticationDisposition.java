package org.loomsip.auth;

/** Result classification after the UAC coordinator observes one SIP response. */
public enum ClientAuthenticationDisposition {
    /** A provisional response remains visible to the application without retrying. */
    PROVISIONAL,
    /** A 401 or 407 challenge started one new authenticated transaction attempt. */
    RETRIED,
    /** A non-challenge final response completed the logical request exchange. */
    COMPLETED
}
