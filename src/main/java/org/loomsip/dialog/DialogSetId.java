package org.loomsip.dialog;

import java.util.Objects;

/**
 * Identity shared by forked Dialogs originating from one local INVITE context.
 *
 * @param callId complete Call-ID
 * @param localTag local endpoint tag
 */
public record DialogSetId(String callId, String localTag) {

    /** Validates Call-ID and local tag syntax. */
    public DialogSetId {
        DialogSyntax.requireCallId(Objects.requireNonNull(callId, "callId"));
        DialogSyntax.requireToken(Objects.requireNonNull(localTag, "localTag"), "local tag");
    }
}
