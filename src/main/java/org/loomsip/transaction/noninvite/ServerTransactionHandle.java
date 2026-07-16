package org.loomsip.transaction.noninvite;

import org.loomsip.message.SipResponse;
import org.loomsip.transaction.TransactionKey;

import java.util.concurrent.CompletionStage;

/**
 * TU handle for responding through a Non-INVITE server transaction.
 */
public interface ServerTransactionHandle {

    /**
     * Returns the transaction identity.
     *
     * @return server transaction key
     */
    TransactionKey key();

    /**
     * Returns the latest externally visible state.
     *
     * @return current state
     */
    NonInviteServerState state();

    /**
     * Queues a provisional or final response for state-machine processing.
     *
     * @param response response correlated to the original request
     */
    void sendResponse(SipResponse response);

    /**
     * Returns a stage completed after termination and ordered TU callbacks drain.
     *
     * @return termination completion
     */
    CompletionStage<Void> terminated();
}
