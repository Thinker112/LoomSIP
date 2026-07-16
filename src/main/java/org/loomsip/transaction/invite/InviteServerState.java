package org.loomsip.transaction.invite;

/** Lifecycle states of an INVITE Server Transaction. */
public enum InviteServerState {
    /** Created but the initial INVITE event has not run. */
    INITIAL,
    /** Initial INVITE delivered while provisional or final response is pending. */
    PROCEEDING,
    /** A 2xx response was sent and retransmitted INVITEs are being absorbed. */
    ACCEPTED,
    /** Non-2xx final response sent while waiting for its ACK. */
    COMPLETED,
    /** Non-2xx ACK received and delayed retransmissions are being absorbed. */
    CONFIRMED,
    /** No longer processing events. */
    TERMINATED
}
