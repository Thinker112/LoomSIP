package org.loomsip.dialog;

/** Timers owned by one Dialog INVITE reliability exchange. */
public enum DialogTimer {
    /** Retransmits a UAS INVITE 2xx over an unreliable transport. */
    TWO_XX_RETRANSMIT,
    /** Bounds the time a UAS waits for the matching 2xx ACK. */
    TWO_XX_ACK_TIMEOUT
}
