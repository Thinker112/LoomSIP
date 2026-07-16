package org.loomsip.transaction.event;

/**
 * Marker for immutable events that may change a transaction state.
 */
public sealed interface TransactionEvent permits
        RequestReceived,
        ResponseReceived,
        ApplicationRequest,
        ApplicationResponse,
        TransportSucceeded,
        TransportFailed,
        TimerExpired,
        CancelRequested,
        TransactionShutdown {
}
