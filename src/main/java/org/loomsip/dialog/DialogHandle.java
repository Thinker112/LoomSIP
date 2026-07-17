package org.loomsip.dialog;

import org.loomsip.message.SipBody;
import org.loomsip.message.SipHeaders;
import org.loomsip.transaction.invite.InviteClientHandle;
import org.loomsip.transaction.noninvite.ClientTransactionHandle;

import java.util.concurrent.CompletionStage;

/** Read-only application handle for a serialized SIP Dialog. */
public interface DialogHandle {

    /**
     * Returns the stable identity of this Dialog.
     *
     * @return stable Dialog identity
     */
    DialogId id();

    /**
     * Returns the latest externally visible state.
     *
     * @return latest immutable state snapshot
     */
    DialogSnapshot snapshot();

    /**
     * Constructs and starts an in-Dialog re-INVITE with a new local CSeq.
     *
     * @param additionalHeaders application headers such as Contact and Content-Type
     * @param body immutable request body
     * @return stage yielding the started INVITE client transaction
     */
    CompletionStage<InviteClientHandle> sendReInvite(
            SipHeaders additionalHeaders,
            SipBody body
    );

    /**
     * Constructs and starts BYE, then terminates the local Dialog.
     *
     * @return stage yielding the started Non-INVITE client transaction after Dialog cleanup
     */
    CompletionStage<ClientTransactionHandle> sendBye();

    /**
     * Returns lifecycle completion for this Dialog.
     *
     * @return stage completed after Dialog termination and callback drainage
     */
    CompletionStage<Void> terminated();
}
