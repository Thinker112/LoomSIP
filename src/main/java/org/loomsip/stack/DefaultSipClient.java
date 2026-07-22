package org.loomsip.stack;

import org.loomsip.transaction.TransactionKeyException;
import org.loomsip.transaction.invite.InviteClientHandle;
import org.loomsip.transaction.invite.InviteTransactionManager;
import org.loomsip.transaction.noninvite.ClientTransactionHandle;
import org.loomsip.transaction.noninvite.NonInviteTransactionManager;

import java.util.Objects;
import java.util.function.Supplier;

/** Internal state-gated implementation of the public Stack client facade. */
final class DefaultSipClient implements SipClient {

    private final Supplier<SipStackState> stateSupplier;
    private final InviteTransactionManager inviteTransactions;
    private final NonInviteTransactionManager nonInviteTransactions;

    DefaultSipClient(
            Supplier<SipStackState> stateSupplier,
            InviteTransactionManager inviteTransactions,
            NonInviteTransactionManager nonInviteTransactions
    ) {
        this.stateSupplier = Objects.requireNonNull(stateSupplier, "stateSupplier");
        this.inviteTransactions = Objects.requireNonNull(inviteTransactions, "inviteTransactions");
        this.nonInviteTransactions = Objects.requireNonNull(nonInviteTransactions, "nonInviteTransactions");
    }

    @Override
    public ClientTransactionHandle request(OutgoingRequest request) throws TransactionKeyException {
        Objects.requireNonNull(request, "request");
        requireRunning();
        return nonInviteTransactions.sendRequest(request.request(), request.target());
    }

    @Override
    public InviteClientHandle invite(InviteRequest request) throws TransactionKeyException {
        Objects.requireNonNull(request, "request");
        requireRunning();
        return inviteTransactions.sendInvite(request.request(), request.target());
    }

    private void requireRunning() {
        if (stateSupplier.get() != SipStackState.RUNNING) {
            throw new IllegalStateException("SIP Stack is not running");
        }
    }
}
