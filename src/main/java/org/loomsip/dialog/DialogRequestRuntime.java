package org.loomsip.dialog;

import org.loomsip.transaction.invite.InviteClientHandle;
import org.loomsip.transaction.noninvite.ClientTransactionHandle;

import java.util.Objects;
import java.util.concurrent.CompletionStage;

/**
 * Runtime services used to resolve and dispatch requests created by a Dialog.
 *
 * @param dialogRuntime shared Dialog I/O and routing runtime
 * @param profile local Via and transport profile
 * @param dispatcher INVITE and Non-INVITE transaction creation boundary
 */
public record DialogRequestRuntime(
        DialogRuntime dialogRuntime,
        DialogRequestProfile profile,
        DialogRequestDispatcher dispatcher
) {

    /** Validates all request runtime services. */
    public DialogRequestRuntime {
        Objects.requireNonNull(dialogRuntime, "dialogRuntime");
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(dispatcher, "dispatcher");
    }

    CompletionStage<InviteClientHandle> sendInvite(DialogPreparedRequest prepared) {
        return dialogRuntime.targetResolver()
                .resolve(prepared.nextHop(), profile.preferredTransport())
                .thenCompose(target -> dialogRuntime.execute(() ->
                        dispatcher.sendInvite(prepared.request(), target)
                ));
    }

    CompletionStage<ClientTransactionHandle> sendNonInvite(DialogPreparedRequest prepared) {
        return dialogRuntime.targetResolver()
                .resolve(prepared.nextHop(), profile.preferredTransport())
                .thenCompose(target -> dialogRuntime.execute(() ->
                        dispatcher.sendNonInvite(prepared.request(), target)
                ));
    }
}
