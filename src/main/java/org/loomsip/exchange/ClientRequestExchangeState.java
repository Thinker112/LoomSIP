package org.loomsip.exchange;

/** Lifecycle state of one logical client request exchange. */
public enum ClientRequestExchangeState {
    /** Created but the initial transaction attempt has not started. */
    NEW,
    /** At least one transaction attempt is active or awaiting a retry decision. */
    ACTIVE,
    /** A final response completed the logical request successfully. */
    COMPLETED,
    /** Attempt creation or request coordination failed. */
    FAILED,
    /** The exchange was explicitly closed before completion. */
    CLOSED
}
