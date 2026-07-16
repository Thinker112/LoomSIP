package org.loomsip.transaction.event;

/**
 * TU command requesting cancellation of an INVITE client transaction.
 */
public record CancelRequested() implements TransactionEvent {
}
