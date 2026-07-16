package org.loomsip.transaction;

import org.loomsip.codec.SipParseException;
import org.loomsip.message.SipMethod;
import org.loomsip.message.SipRequest;
import org.loomsip.message.SipResponse;
import org.loomsip.message.header.SipHeaderValueException;
import org.loomsip.message.header.SipHeaderValues;
import org.loomsip.transaction.invite.InviteTransactionManager;
import org.loomsip.transaction.noninvite.NonInviteTransactionManager;
import org.loomsip.transport.InboundSipMessage;
import org.loomsip.transport.SipMessageHandler;
import org.loomsip.transport.TransportContext;

import java.util.Objects;

/**
 * Top-level transport dispatcher for INVITE and Non-INVITE transaction managers.
 *
 * <pre>{@code
 * Netty / SipTransport
 *          |
 *          v
 * SipTransactionDispatcher
 *   | request Method / response CSeq Method
 *   |
 *   +--> INVITE or ACK ------> InviteTransactionManager
 *   |
 *   +--> CANCEL / other -----> NonInviteTransactionManager
 * }</pre>
 *
 * <p>The dispatcher does not own or close either manager. CANCEL association is
 * performed by {@code CancelCoordinator} from the CANCEL NIST callback.</p>
 */
public final class SipTransactionDispatcher implements SipMessageHandler {

    private final InviteTransactionManager inviteTransactions;
    private final NonInviteTransactionManager nonInviteTransactions;

    /**
     * Creates a dispatcher over existing transaction managers.
     *
     * @param inviteTransactions INVITE and ACK routing target
     * @param nonInviteTransactions CANCEL and other method routing target
     */
    public SipTransactionDispatcher(
            InviteTransactionManager inviteTransactions,
            NonInviteTransactionManager nonInviteTransactions
    ) {
        this.inviteTransactions = Objects.requireNonNull(inviteTransactions, "inviteTransactions");
        this.nonInviteTransactions = Objects.requireNonNull(nonInviteTransactions, "nonInviteTransactions");
    }

    @Override
    public void onMessage(InboundSipMessage inbound) {
        Objects.requireNonNull(inbound, "inbound");
        if (inbound.message() instanceof SipRequest request) {
            routeRequest(request).onMessage(inbound);
            return;
        }
        try {
            SipMethod method = SipHeaderValues.cseq(((SipResponse) inbound.message()).headers()).method();
            routeMethod(method).onMessage(inbound);
        } catch (SipHeaderValueException exception) {
            onTransportError(new TransactionKeyException(
                    "cannot route response with malformed CSeq",
                    exception
            ));
        }
    }

    @Override
    public void onMalformedMessage(TransportContext context, SipParseException cause) {
        inviteTransactions.onMalformedMessage(context, cause);
        nonInviteTransactions.onMalformedMessage(context, cause);
    }

    @Override
    public void onTransportError(Throwable cause) {
        inviteTransactions.onTransportError(cause);
        nonInviteTransactions.onTransportError(cause);
    }

    private SipMessageHandler routeRequest(SipRequest request) {
        return routeMethod(request.method());
    }

    private SipMessageHandler routeMethod(SipMethod method) {
        return SipMethod.INVITE.equals(method) || SipMethod.ACK.equals(method)
                ? inviteTransactions
                : nonInviteTransactions;
    }
}
