package org.loomsip.message.header;

import org.loomsip.message.SipMethod;

import java.util.Objects;

/**
 * Typed RFC 3262 PRACK correlation value.
 *
 * @param responseSequenceNumber acknowledged RSeq value
 * @param inviteSequenceNumber INVITE CSeq value
 * @param inviteMethod method from the acknowledged CSeq, normally INVITE
 */
public record RAckHeaderValue(
        long responseSequenceNumber,
        long inviteSequenceNumber,
        SipMethod inviteMethod
) {

    /** Validates RSeq, CSeq, and CSeq method components. */
    public RAckHeaderValue {
        new RSeqHeaderValue(responseSequenceNumber);
        if (inviteSequenceNumber < 0 || inviteSequenceNumber > CSeqHeaderValue.MAX_SEQUENCE_NUMBER) {
            throw new IllegalArgumentException(
                    "RAck CSeq must be between 0 and " + CSeqHeaderValue.MAX_SEQUENCE_NUMBER
            );
        }
        inviteMethod = Objects.requireNonNull(inviteMethod, "inviteMethod");
    }

    /**
     * Renders this value for a RAck header.
     *
     * @return {@code rseq cseq method} wire value
     */
    public String wireValue() {
        return responseSequenceNumber + " " + inviteSequenceNumber + " " + inviteMethod.value();
    }
}
