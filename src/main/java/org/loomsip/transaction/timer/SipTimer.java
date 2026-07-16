package org.loomsip.transaction.timer;

/**
 * Named timers used by RFC 3261 and RFC 6026 transaction state machines.
 */
public enum SipTimer {
    /** INVITE client retransmission. */
    A,
    /** INVITE client total timeout. */
    B,
    /** INVITE client non-2xx absorption interval. */
    D,
    /** Non-INVITE client retransmission. */
    E,
    /** Non-INVITE client total timeout. */
    F,
    /** INVITE server non-2xx response retransmission. */
    G,
    /** INVITE server ACK wait timeout. */
    H,
    /** INVITE server confirmed absorption interval. */
    I,
    /** Non-INVITE server completed absorption interval. */
    J,
    /** Non-INVITE client completed absorption interval. */
    K,
    /** RFC 6026 INVITE server accepted lifetime. */
    L,
    /** RFC 6026 INVITE client accepted lifetime. */
    M
}
