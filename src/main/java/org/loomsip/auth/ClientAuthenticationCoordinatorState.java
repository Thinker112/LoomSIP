package org.loomsip.auth;

/** Lifecycle state of one UAC authentication coordinator. */
public enum ClientAuthenticationCoordinatorState {
    /** Initial transaction attempt has not been requested. */
    NEW,
    /** Waiting for the initial exchange attempt factory. */
    STARTING,
    /** Ready to accept SIP responses for the current attempt. */
    ACTIVE,
    /** Waiting for asynchronous credential lookup. */
    AWAITING_CREDENTIAL,
    /** Waiting for the caller to rebuild a request with a new branch/CSeq. */
    BUILDING_RETRY,
    /** Waiting for the exchange to start the rebuilt transaction attempt. */
    STARTING_RETRY,
    /** Waiting for a non-challenge final response to complete the exchange. */
    COMPLETING,
    /** Completed with a non-challenge final response. */
    COMPLETED,
    /** Failed while parsing, looking up credentials, or creating a retry. */
    FAILED,
    /** Explicitly closed before normal completion. */
    CLOSED
}
