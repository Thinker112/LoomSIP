package org.loomsip.transaction.noninvite;

/**
 * Lifecycle states of a Non-INVITE Server Transaction.
 */
public enum NonInviteServerState {
    /** Created but the first request has not yet been consumed. */
    INITIAL,
    /** Initial request delivered to TU with no provisional response sent. */
    TRYING,
    /** A provisional response has been sent. */
    PROCEEDING,
    /** A final response has been sent and duplicates are being absorbed. */
    COMPLETED,
    /** No longer processing events. */
    TERMINATED
}
