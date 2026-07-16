package org.loomsip.transaction.invite;

/** Lifecycle states of an INVITE Client Transaction. */
public enum InviteClientState {
    /** Created and registered but not yet sent. */
    INITIAL,
    /** INVITE sent while waiting for a response. */
    CALLING,
    /** At least one provisional response received. */
    PROCEEDING,
    /** A 2xx response was received and additional matching 2xx responses remain deliverable. */
    ACCEPTED,
    /** Non-2xx final response received and ACK generated. */
    COMPLETED,
    /** No longer processing events. */
    TERMINATED
}
