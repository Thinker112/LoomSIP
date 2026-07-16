package org.loomsip.transaction;

import org.loomsip.message.SipMethod;

/**
 * Stable identity used to route messages to a client or server transaction.
 */
public sealed interface TransactionKey
        permits Rfc3261TransactionKey, LegacyTransactionKey {

    /**
     * Returns the method used for transaction matching.
     *
     * @return transaction method
     */
    SipMethod method();
}
