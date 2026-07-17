package org.loomsip.dialog;

import org.loomsip.message.SipRequest;
import org.loomsip.transaction.TransactionKeyException;
import org.loomsip.transaction.invite.InviteClientHandle;
import org.loomsip.transaction.noninvite.ClientTransactionHandle;
import org.loomsip.transport.TransportEndpoint;

/**
 * Transaction creation boundary used by Dialog-owned outbound requests.
 *
 * <pre>{@code
 * Dialog Mailbox -> immutable request -> DialogRequestDispatcher
 *                                           |
 *                         +-----------------+-----------------+
 *                         v                                   v
 *               InviteTransactionManager          NonInviteTransactionManager
 * }</pre>
 */
public interface DialogRequestDispatcher {

    /**
     * Creates an INVITE client transaction for an in-Dialog re-INVITE.
     *
     * @param request immutable re-INVITE
     * @param target resolved next hop
     * @return started INVITE client transaction
     * @throws TransactionKeyException if transaction headers are malformed
     */
    InviteClientHandle sendInvite(SipRequest request, TransportEndpoint target)
            throws TransactionKeyException;

    /**
     * Creates a Non-INVITE client transaction for an in-Dialog request.
     *
     * @param request immutable Non-INVITE request
     * @param target resolved next hop
     * @return started Non-INVITE client transaction
     * @throws TransactionKeyException if transaction headers are malformed
     */
    ClientTransactionHandle sendNonInvite(SipRequest request, TransportEndpoint target)
            throws TransactionKeyException;
}
