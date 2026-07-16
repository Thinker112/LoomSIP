package org.loomsip.transaction;

/**
 * Minimal identity contract shared by transaction implementations and repositories.
 */
public interface SipTransaction {

    /**
     * Returns the immutable repository key.
     *
     * @return transaction key
     */
    TransactionKey key();
}
