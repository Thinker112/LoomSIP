package org.loomsip.codec;

public final class SipParseException extends Exception {

    private final int byteOffset;

    public SipParseException(String message, int byteOffset) {
        super(message + " at byte offset " + byteOffset);
        this.byteOffset = byteOffset;
    }

    public SipParseException(String message, int byteOffset, Throwable cause) {
        super(message + " at byte offset " + byteOffset, cause);
        this.byteOffset = byteOffset;
    }

    public int byteOffset() {
        return byteOffset;
    }
}
