package org.loomsip.transaction.invite;

/**
 * Capacity limits for one INVITE transaction manager.
 *
 * @param clientTransactions maximum active client transactions
 * @param serverTransactions maximum active server transactions
 * @param mailboxCapacity maximum queued events per transaction
 * @param callbackCapacity maximum queued TU callbacks per transaction
 */
public record InviteTransactionConfig(
        int clientTransactions,
        int serverTransactions,
        int mailboxCapacity,
        int callbackCapacity
) {

    /** Conservative initial capacities. */
    public static final InviteTransactionConfig DEFAULT = new InviteTransactionConfig(
            10_000,
            10_000,
            256,
            64
    );

    /** Validates that every configured capacity is positive. */
    public InviteTransactionConfig {
        if (clientTransactions <= 0
                || serverTransactions <= 0
                || mailboxCapacity <= 0
                || callbackCapacity <= 0) {
            throw new IllegalArgumentException("all INVITE transaction capacities must be positive");
        }
    }
}
