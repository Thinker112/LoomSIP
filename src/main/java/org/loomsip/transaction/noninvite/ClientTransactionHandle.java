package org.loomsip.transaction.noninvite;

import org.loomsip.transaction.TransactionKey;

import java.util.concurrent.CompletionStage;

/**
 * Read-only handle returned when a Non-INVITE client transaction starts.
 */
public interface ClientTransactionHandle {

    /**
     * Returns the transaction identity.
     *
     * @return client transaction key
     */
    TransactionKey key();

    /**
     * Returns the latest externally visible state.
     *
     * @return current state
     */
    NonInviteClientState state();

    /**
     * Returns a stage completed after termination and ordered TU callbacks drain.
     *
     * @return termination completion
     */
    CompletionStage<Void> terminated();
}
