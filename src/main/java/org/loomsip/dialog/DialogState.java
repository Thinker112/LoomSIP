package org.loomsip.dialog;

/** SIP Dialog lifecycle state. */
public enum DialogState {
    /** A tagged provisional response established an early Dialog. */
    EARLY,
    /** A 2xx response confirmed the Dialog. */
    CONFIRMED,
    /** The Dialog no longer accepts ordinary protocol commands. */
    TERMINATED
}
