package org.loomsip.transaction.invite;

import org.loomsip.message.SipRequest;
import org.loomsip.transaction.TransactionKey;

import java.util.concurrent.CompletionStage;

/** Read-only handle returned when an INVITE client transaction starts. */
public interface InviteClientHandle {

    /**
     * Returns the client transaction identity.
     *
     * @return client transaction identity
     */
    TransactionKey key();

    /**
     * Returns the immutable INVITE that created this transaction.
     *
     * <p>Higher protocol layers use the request to correlate Dialog identity
     * and routing state without maintaining a registration race beside the
     * transaction repository.</p>
     *
     * @return original INVITE request
     */
    SipRequest originalRequest();

    /**
     * Returns the latest externally visible state.
     *
     * @return current ICT state
     */
    InviteClientState state();

    /**
     * Returns the termination completion.
     *
     * @return stage completed after termination and ordered TU callbacks drain
     */
    CompletionStage<Void> terminated();
}
