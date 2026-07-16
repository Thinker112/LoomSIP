package org.loomsip.message.header;

import org.loomsip.message.SipMethod;

import java.util.Objects;

/**
 * Typed CSeq field value.
 *
 * @param sequenceNumber unsigned sequence value below 2^31
 * @param method CSeq method token
 */
public record CSeqHeaderValue(long sequenceNumber, SipMethod method) {

    /** Maximum CSeq value permitted by RFC 3261. */
    public static final long MAX_SEQUENCE_NUMBER = 2_147_483_647L;

    /**
     * Validates and creates a CSeq value.
     *
     * @throws NullPointerException if {@code method} is {@code null}
     * @throws IllegalArgumentException if the sequence is outside its valid range
     */
    public CSeqHeaderValue {
        Objects.requireNonNull(method, "method");
        if (sequenceNumber < 0 || sequenceNumber > MAX_SEQUENCE_NUMBER) {
            throw new IllegalArgumentException("CSeq must be between 0 and " + MAX_SEQUENCE_NUMBER);
        }
    }
}
