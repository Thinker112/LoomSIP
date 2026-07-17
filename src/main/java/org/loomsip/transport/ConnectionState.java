package org.loomsip.transport;

/** Lifecycle state of one reliable transport connection. */
public enum ConnectionState {
    /** A connect operation is in progress. */
    CONNECTING,
    /** The channel is active and accepts writes. */
    ACTIVE,
    /** Local shutdown has started and new writes are rejected. */
    CLOSING,
    /** The connection failed before normal closure completed. */
    FAILED,
    /** The channel is closed and all pending writes have completed. */
    CLOSED
}
