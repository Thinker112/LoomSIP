package org.loomsip.codec;

/**
 * Checked failure describing invalid SIP wire data and its approximate byte position.
 */
public final class SipParseException extends Exception {

    /** Zero-based location at or near the invalid wire data. */
    private final int byteOffset;

    /**
     * Creates a parse failure without an underlying cause.
     *
     * @param message failure description without the offset suffix
     * @param byteOffset zero-based byte offset at or near the invalid input
     */
    public SipParseException(String message, int byteOffset) {
        super(message + " at byte offset " + byteOffset);
        this.byteOffset = byteOffset;
    }

    /**
     * Creates a parse failure caused by a lower-level validation or decoding error.
     *
     * @param message failure description without the offset suffix
     * @param byteOffset zero-based byte offset at or near the invalid input
     * @param cause underlying failure
     */
    public SipParseException(String message, int byteOffset, Throwable cause) {
        super(message + " at byte offset " + byteOffset, cause);
        this.byteOffset = byteOffset;
    }

    /**
     * Returns the zero-based location associated with the failure.
     *
     * @return byte offset at or near the invalid input
     */
    public int byteOffset() {
        return byteOffset;
    }
}
