package org.loomsip.transaction.invite;

import org.loomsip.message.SipMethod;
import org.loomsip.message.SipRequest;
import org.loomsip.message.SipResponses;
import org.loomsip.transaction.TransactionKeyException;
import org.loomsip.transaction.noninvite.ClientTransactionHandle;
import org.loomsip.transaction.noninvite.NonInviteTransactionManager;
import org.loomsip.transaction.noninvite.ServerTransactionHandle;
import org.loomsip.transport.TransportContext;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Coordinates independent CANCEL transactions with their related INVITE transactions.
 *
 * <pre>{@code
 * Client TU                       Server CANCEL NIST
 *    |                                   |
 *    v                                   v
 * sendCancel(ICT)                 handleInboundCancel
 *    |                                   |
 *    v                                   +--> related INVITE key --> IST
 * CANCEL NICT                            |          |
 *                                        |          +--> TU onCancel if Proceeding
 *                                        v
 *                                  200 or 481 response
 * }</pre>
 *
 * <p>CANCEL remains a normal Non-INVITE transaction. This coordinator owns only
 * cross-transaction association and the CANCEL response policy.</p>
 */
public final class CancelCoordinator {

    private final InviteTransactionManager inviteTransactions;
    private final NonInviteTransactionManager nonInviteTransactions;
    private final AtomicLong nextTag = new AtomicLong();

    /**
     * Creates a coordinator over existing INVITE and Non-INVITE managers.
     *
     * @param inviteTransactions INVITE manager used for association
     * @param nonInviteTransactions Non-INVITE manager used for CANCEL NICT
     */
    public CancelCoordinator(
            InviteTransactionManager inviteTransactions,
            NonInviteTransactionManager nonInviteTransactions
    ) {
        this.inviteTransactions = Objects.requireNonNull(inviteTransactions, "inviteTransactions");
        this.nonInviteTransactions = Objects.requireNonNull(nonInviteTransactions, "nonInviteTransactions");
    }

    /**
     * Creates and starts an independent CANCEL client transaction.
     *
     * @param invite active ICT in Calling or Proceeding
     * @return CANCEL NICT handle
     * @throws TransactionKeyException if CANCEL routing fields cannot form a key
     * @throws IllegalArgumentException if the handle is not owned by the INVITE manager
     * @throws IllegalStateException if the INVITE is no longer cancellable
     */
    public ClientTransactionHandle sendCancel(InviteClientHandle invite) throws TransactionKeyException {
        InviteTransactionManager.CancellationSource source = inviteTransactions.cancellationSource(invite);
        SipRequest cancel = InviteCancellations.createCancel(source.invite());
        return nonInviteTransactions.sendRequest(cancel, source.target());
    }

    /**
     * Associates an inbound CANCEL and answers its independent server transaction.
     *
     * <p>A matched CANCEL receives 200 even when the original IST has already sent
     * a final response. Only a still-Proceeding IST emits {@code onCancel} to TU.</p>
     *
     * @param cancelTransaction response-capable CANCEL NIST
     * @param cancel inbound CANCEL
     * @param context CANCEL network metadata
     * @return association result
     * @throws TransactionKeyException if the related INVITE key is malformed
     */
    public CancelMatch handleInboundCancel(
            ServerTransactionHandle cancelTransaction,
            SipRequest cancel,
            TransportContext context
    ) throws TransactionKeyException {
        Objects.requireNonNull(cancelTransaction, "cancelTransaction");
        Objects.requireNonNull(cancel, "cancel");
        Objects.requireNonNull(context, "context");
        if (!SipMethod.CANCEL.equals(cancel.method())) {
            throw new IllegalArgumentException("expected CANCEL request but got " + cancel.method());
        }

        boolean matched = inviteTransactions.requestCancellation(cancel, context);
        int status = matched ? 200 : 481;
        String reason = matched ? "OK" : "Call/Transaction Does Not Exist";
        cancelTransaction.sendResponse(SipResponses.createResponse(
                cancel,
                status,
                reason,
                "loomsip-cancel-" + nextTag.incrementAndGet()
        ));
        return matched ? CancelMatch.MATCHED : CancelMatch.UNMATCHED;
    }
}
