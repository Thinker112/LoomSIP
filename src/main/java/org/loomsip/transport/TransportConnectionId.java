package org.loomsip.transport;

import java.util.Objects;

/**
 * Stable identifier of one accepted or established transport connection.
 *
 * @param value transport-specific opaque identifier
 */
public record TransportConnectionId(String value) {

    /** Validates the opaque identifier. */
    public TransportConnectionId {
        Objects.requireNonNull(value, "value");
        if (value.isBlank()) {
            throw new IllegalArgumentException("connection identifier must not be blank");
        }
    }
}
