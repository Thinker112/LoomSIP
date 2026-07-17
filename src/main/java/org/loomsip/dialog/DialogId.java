package org.loomsip.dialog;

import org.loomsip.message.SipHeaders;
import org.loomsip.message.header.SipHeaderValueException;
import org.loomsip.message.header.SipHeaderValues;

import java.util.Objects;

/**
 * Stable Dialog identity expressed from the local endpoint's perspective.
 *
 * @param callId complete Call-ID
 * @param localTag local endpoint tag
 * @param remoteTag remote endpoint tag
 */
public record DialogId(
        String callId,
        String localTag,
        String remoteTag
) {

    /** Validates Call-ID and tag syntax. */
    public DialogId {
        DialogSyntax.requireCallId(Objects.requireNonNull(callId, "callId"));
        DialogSyntax.requireToken(Objects.requireNonNull(localTag, "localTag"), "local tag");
        DialogSyntax.requireToken(Objects.requireNonNull(remoteTag, "remoteTag"), "remote tag");
    }

    /**
     * Derives a Dialog identity from SIP headers using the local endpoint role.
     *
     * <p>For a UAC, From is local and To is remote. For a UAS, To is local and
     * From is remote.</p>
     *
     * @param headers dialog-forming request or response headers
     * @param role local endpoint role
     * @return role-oriented Dialog identity
     * @throws SipHeaderValueException if Call-ID or either tag is absent or malformed
     */
    public static DialogId from(SipHeaders headers, DialogRole role) throws SipHeaderValueException {
        Objects.requireNonNull(headers, "headers");
        Objects.requireNonNull(role, "role");
        String fromTag = SipHeaderValues.fromTag(headers).orElseThrow(
                () -> new SipHeaderValueException("From tag is required for a Dialog ID")
        );
        String toTag = SipHeaderValues.toTag(headers).orElseThrow(
                () -> new SipHeaderValueException("To tag is required for a Dialog ID")
        );
        return switch (role) {
            case UAC -> new DialogId(SipHeaderValues.callId(headers), fromTag, toTag);
            case UAS -> new DialogId(SipHeaderValues.callId(headers), toTag, fromTag);
        };
    }

    /**
     * Returns the fork-related Dialog Set identity.
     *
     * @return Call-ID and local-tag pair
     */
    public DialogSetId setId() {
        return new DialogSetId(callId, localTag);
    }
}
