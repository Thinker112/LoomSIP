package org.loomsip.transport;

/**
 * Lifecycle state of a one-shot SIP transport instance.
 *
 * <p>A transport normally moves from {@link #NEW} through {@link #STARTING}
 * and {@link #RUNNING}, then through {@link #CLOSING} to {@link #CLOSED}.
 * Startup or unexpected channel failures may enter {@link #FAILED}. Closed and
 * failed transport instances cannot be restarted.</p>
 */
public enum TransportState {
    /** Constructed but not started. */
    NEW,
    /** Allocating resources and binding a local endpoint. */
    STARTING,
    /** Bound and accepting inbound and outbound messages. */
    RUNNING,
    /** Rejecting new work while owned resources are released. */
    CLOSING,
    /** Successfully closed and no longer reusable. */
    CLOSED,
    /** Failed to start or lost its channel unexpectedly. */
    FAILED
}
