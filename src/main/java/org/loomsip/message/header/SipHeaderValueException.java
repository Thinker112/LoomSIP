package org.loomsip.message.header;

/**
 * Checked failure to parse a typed value from a SIP header field.
 */
public final class SipHeaderValueException extends Exception {

    /**
     * Creates a typed-header parsing failure.
     *
     * @param message failure description
     */
    public SipHeaderValueException(String message) {
        super(message);
    }

    /**
     * Creates a typed-header parsing failure with its underlying cause.
     *
     * @param message failure description
     * @param cause underlying validation or numeric conversion failure
     */
    public SipHeaderValueException(String message, Throwable cause) {
        super(message, cause);
    }
}
