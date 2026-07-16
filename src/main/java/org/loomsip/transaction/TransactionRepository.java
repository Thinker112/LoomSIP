package org.loomsip.transaction;

import java.util.Optional;
import java.util.function.Supplier;

/**
 * Concurrent index of active SIP transactions.
 *
 * <pre>{@code
 * TransactionKey
 *       |
 *       v
 * +-----------------------+
 * | TransactionRepository |
 * | - find                |
 * | - register            |
 * | - getOrCreate         |
 * | - identity remove     |
 * +-----------+-----------+
 *             |
 *             v
 *       SipTransaction
 *       +-----------+
 *       | Mailbox   |
 *       | Timers    |
 *       | State     |
 *       +-----------+
 * }</pre>
 */
public interface TransactionRepository {

    /**
     * Finds an active transaction.
     *
     * @param key transaction identity
     * @return matching transaction, or empty
     */
    Optional<SipTransaction> find(TransactionKey key);

    /**
     * Registers a previously created transaction.
     *
     * @param transaction transaction to register
     * @throws TransactionRepositoryException if the key exists or capacity is exhausted
     */
    void register(SipTransaction transaction);

    /**
     * Returns an existing transaction or atomically registers one created by the supplier.
     *
     * <p>The supplier may run concurrently and must not publish or start the
     * candidate before this method returns it as the registered instance.</p>
     *
     * @param key transaction identity
     * @param factory side-effect-free candidate factory
     * @return existing or newly registered transaction
     * @throws TransactionRepositoryException if capacity is exhausted or the candidate key differs
     */
    SipTransaction getOrCreate(TransactionKey key, Supplier<? extends SipTransaction> factory);

    /**
     * Removes a transaction only when the currently registered object is the expected instance.
     *
     * @param key transaction identity
     * @param expected exact registered instance
     * @return {@code true} when removed
     */
    boolean remove(TransactionKey key, SipTransaction expected);

    /**
     * Returns the current active transaction count.
     *
     * @return active count
     */
    int size();

    /**
     * Returns the configured active transaction limit.
     *
     * @return maximum active transactions
     */
    int capacity();
}
