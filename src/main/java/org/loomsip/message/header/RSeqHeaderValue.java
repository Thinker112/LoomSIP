package org.loomsip.message.header;

/**
 * Typed RFC 3262 reliable provisional response sequence number.
 *
 * @param sequenceNumber positive 31-bit RSeq value
 */
public record RSeqHeaderValue(long sequenceNumber) {

    /** Largest RSeq value accepted by the initial implementation. */
    public static final long MAX_SEQUENCE_NUMBER = 2_147_483_647L;

    /** Validates the positive RFC sequence range. */
    public RSeqHeaderValue {
        if (sequenceNumber <= 0 || sequenceNumber > MAX_SEQUENCE_NUMBER) {
            throw new IllegalArgumentException(
                    "RSeq must be between 1 and " + MAX_SEQUENCE_NUMBER
            );
        }
    }

    /**
     * Renders this value for an RSeq header.
     *
     * @return decimal sequence number
     */
    public String wireValue() {
        return Long.toString(sequenceNumber);
    }
}
