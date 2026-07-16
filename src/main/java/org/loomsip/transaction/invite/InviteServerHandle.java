package org.loomsip.transaction.invite;

import org.loomsip.message.SipResponse;
import org.loomsip.transaction.TransactionKey;

import java.util.concurrent.CompletionStage;

/** TU handle for responding through an INVITE server transaction. */
public interface InviteServerHandle {

    /**
     * Returns the server transaction identity.
     *
     * @return server transaction identity
     */
    TransactionKey key();

    /**
     * Returns the latest externally visible state.
     *
     * @return current IST state
     */
    InviteServerState state();

    /**
     * Queues a provisional or final response for state-machine processing.
     *
     * @param response response correlated to the initial INVITE
     */
    void sendResponse(SipResponse response);

    /**
     * Returns the termination completion.
     *
     * @return stage completed after termination and ordered TU callbacks drain
     */
    CompletionStage<Void> terminated();
}
