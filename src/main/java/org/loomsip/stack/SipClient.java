package org.loomsip.stack;

import org.loomsip.transaction.TransactionKeyException;
import org.loomsip.transaction.invite.InviteClientHandle;
import org.loomsip.transaction.noninvite.ClientTransactionHandle;

/**
 * Stack-owned facade for explicit out-of-dialog SIP commands.
 *
 * <pre>{@code
 * application --> SipClient --> Transaction Manager --> TransportRegistry
 * }</pre>
 */
public interface SipClient {

    /**
     * Starts a generic Non-INVITE client transaction.
     *
     * @param request immutable SIP request and explicit target
     * @return started Non-INVITE transaction handle
     * @throws TransactionKeyException if transaction correlation headers are invalid
     * @throws IllegalStateException if the Stack is not running
     */
    ClientTransactionHandle request(OutgoingRequest request) throws TransactionKeyException;

    /**
     * Starts an initial INVITE client transaction.
     *
     * @param request immutable INVITE and explicit target
     * @return started INVITE transaction handle
     * @throws TransactionKeyException if transaction correlation headers are invalid
     * @throws IllegalStateException if the Stack is not running
     */
    InviteClientHandle invite(InviteRequest request) throws TransactionKeyException;
}
