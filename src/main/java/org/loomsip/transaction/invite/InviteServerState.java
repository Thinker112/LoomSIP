package org.loomsip.transaction.invite;

/** Lifecycle states of an INVITE Server Transaction. */
public enum InviteServerState {
    /** Created but the initial INVITE event has not run. */
    INITIAL,
    /** Initial INVITE delivered while provisional or final response is pending. */
    PROCEEDING,
    /** Final response sent while waiting for a non-2xx ACK or local 2xx write completion. */
    COMPLETED,
    /** Non-2xx ACK received and delayed retransmissions are being absorbed. */
    CONFIRMED,
    /** No longer processing events. */
    TERMINATED
}
