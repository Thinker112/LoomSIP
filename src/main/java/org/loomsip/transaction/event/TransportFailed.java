package org.loomsip.transaction.event;

import java.util.Objects;

/**
 * Failed completion of a transaction-owned local transport write.
 *
 * @param operationId transaction-generated write generation
 * @param cause transport failure
 */
public record TransportFailed(
        long operationId,
        Throwable cause
) implements TransactionEvent {

    /**
     * Validates and creates a transport-failure event.
     *
     * @throws NullPointerException if {@code cause} is {@code null}
     * @throws IllegalArgumentException if operationId is negative
     */
    public TransportFailed {
        Objects.requireNonNull(cause, "cause");
        if (operationId < 0) {
            throw new IllegalArgumentException("operationId must not be negative");
        }
    }
}
