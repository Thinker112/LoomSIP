package org.loomsip.transaction.event;

/**
 * Internal command requesting deterministic transaction shutdown.
 */
public record TransactionShutdown() implements TransactionEvent {
}
