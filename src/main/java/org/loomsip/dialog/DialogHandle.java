package org.loomsip.dialog;

import org.loomsip.message.SipBody;
import org.loomsip.message.SipHeaders;
import org.loomsip.message.SipMethod;
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
     * Constructs and starts a generic Non-INVITE request in this Dialog.
     *
     * <p>The Dialog allocates CSeq, Via branch, Route Set, and Remote Target.
     * INVITE, BYE, ACK, and CANCEL require their dedicated APIs and are rejected
     * here. Extension-specific headers and behavior remain the caller's
     * responsibility until the corresponding extension stage is implemented.</p>
     *
     * @param method extension Non-INVITE method
     * @param additionalHeaders application or extension headers not managed by Dialog routing
     * @param body immutable request body
     * @return stage yielding the started Non-INVITE transaction
     */
    CompletionStage<ClientTransactionHandle> sendRequest(
            SipMethod method,
            SipHeaders additionalHeaders,
            SipBody body
    );

    /**
     * Returns lifecycle completion for this Dialog.
     *
     * @return stage completed after Dialog termination and callback drainage
     */
    CompletionStage<Void> terminated();
}
