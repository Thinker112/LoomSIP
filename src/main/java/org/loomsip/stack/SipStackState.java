package org.loomsip.stack;

/**
 * Lifecycle state of one {@link LoomSipStack} runtime.
 *
 * <pre>{@code
 * NEW -> STARTING -> RUNNING -> CLOSING -> CLOSED
 *          |
 *          +-> FAILED
 * }</pre>
 */
public enum SipStackState {
    /** Built but not yet started. */
    NEW,
    /** Starting owned transports while startup callers await the outcome. */
    STARTING,
    /** All configured transports started and the stack accepts commands. */
    RUNNING,
    /** Rejecting new work while owned resources are released. */
    CLOSING,
    /** Fully closed and not restartable. */
    CLOSED,
    /** Startup failed; callers may close the stack to observe final cleanup. */
    FAILED
}
