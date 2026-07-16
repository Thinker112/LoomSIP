package org.loomsip.transaction.noninvite;

/**
 * Lifecycle states of a Non-INVITE Client Transaction.
 */
public enum NonInviteClientState {
    /** Created and registered but not yet sent. */
    INITIAL,
    /** Request sent while waiting for any response. */
    TRYING,
    /** At least one provisional response received. */
    PROCEEDING,
    /** Final response received and absorbing delayed UDP responses. */
    COMPLETED,
    /** No longer processing events. */
    TERMINATED
}
