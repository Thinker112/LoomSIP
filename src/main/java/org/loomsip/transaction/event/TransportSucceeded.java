package org.loomsip.transaction.event;

import org.loomsip.transport.SendResult;

import java.util.Objects;

/**
 * Successful completion of a transaction-owned local transport write.
 *
 * @param operationId transaction-generated write generation
 * @param result local transport result
 */
public record TransportSucceeded(
        long operationId,
        SendResult result
) implements TransactionEvent {

    /**
     * Validates and creates a transport-success event.
     *
     * @throws NullPointerException if {@code result} is {@code null}
     * @throws IllegalArgumentException if operationId is negative
     */
    public TransportSucceeded {
        Objects.requireNonNull(result, "result");
        if (operationId < 0) {
            throw new IllegalArgumentException("operationId must not be negative");
        }
    }
}
