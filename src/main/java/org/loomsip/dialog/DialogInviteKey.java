package org.loomsip.dialog;

import org.loomsip.message.header.CSeqHeaderValue;

import java.util.Objects;

/**
 * Identity of one INVITE exchange whose 2xx reliability belongs to a Dialog.
 *
 * @param dialogId Dialog identity
 * @param inviteCSeq INVITE sequence number
 */
public record DialogInviteKey(DialogId dialogId, long inviteCSeq) {

    /** Validates the Dialog identity and SIP CSeq range. */
    public DialogInviteKey {
        Objects.requireNonNull(dialogId, "dialogId");
        if (inviteCSeq < 0 || inviteCSeq > CSeqHeaderValue.MAX_SEQUENCE_NUMBER) {
            throw new IllegalArgumentException("INVITE CSeq must be a valid SIP sequence number");
        }
    }
}
