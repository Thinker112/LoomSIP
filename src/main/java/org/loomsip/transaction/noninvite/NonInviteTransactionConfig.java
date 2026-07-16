package org.loomsip.transaction.noninvite;

/**
 * Capacity limits for one Non-INVITE transaction manager.
 *
 * @param clientTransactions maximum active client transactions
 * @param serverTransactions maximum active server transactions
 * @param mailboxCapacity maximum queued state-machine events per transaction
 * @param callbackCapacity maximum queued TU callbacks per transaction
 */
public record NonInviteTransactionConfig(
        int clientTransactions,
        int serverTransactions,
        int mailboxCapacity,
        int callbackCapacity
) {

    /** Conservative default capacities for an initial standalone stack. */
    public static final NonInviteTransactionConfig DEFAULT = new NonInviteTransactionConfig(
            10_000,
            10_000,
            256,
            64
    );

    /**
     * Validates transaction capacities.
     *
     * @throws IllegalArgumentException if any capacity is not positive
     */
    public NonInviteTransactionConfig {
        if (clientTransactions <= 0
                || serverTransactions <= 0
                || mailboxCapacity <= 0
                || callbackCapacity <= 0) {
            throw new IllegalArgumentException("all Non-INVITE transaction capacities must be positive");
        }
    }
}
