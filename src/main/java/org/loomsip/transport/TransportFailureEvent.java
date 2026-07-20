package org.loomsip.transport;

import java.util.Objects;

/**
 * Immutable failure emitted at the transport-send boundary.
 *
 * @param target destination that could not be written
 * @param cause underlying transport failure
 */
public record TransportFailureEvent(
        TransportEndpoint target,
        Throwable cause
) {

    /** Validates failure event fields. */
    public TransportFailureEvent {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(cause, "cause");
    }

    /**
     * Returns the failed transport protocol.
     *
     * @return protocol carried by the failed target
     */
    public TransportProtocol protocol() {
        return target.protocol();
    }
}
