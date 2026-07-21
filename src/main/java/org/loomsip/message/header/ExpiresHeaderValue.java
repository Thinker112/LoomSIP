package org.loomsip.message.header;

/** Typed non-negative Expires interval used by SUBSCRIBE requests and responses. */
public record ExpiresHeaderValue(int seconds) {

    /** Validates the RFC 3265 interval range. */
    public ExpiresHeaderValue {
        if (seconds < 0) {
            throw new IllegalArgumentException("Expires seconds must not be negative");
        }
    }

    /** @return decimal Expires wire value */
    public String wireValue() {
        return Integer.toString(seconds);
    }
}
