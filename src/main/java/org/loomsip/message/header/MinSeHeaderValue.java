package org.loomsip.message.header;

/** Typed RFC 4028 Min-SE interval. */
public record MinSeHeaderValue(int intervalSeconds) {

    /** Validates a positive minimum session interval. */
    public MinSeHeaderValue {
        if (intervalSeconds <= 0) {
            throw new IllegalArgumentException("Min-SE interval must be positive");
        }
    }

    /** @return decimal Min-SE wire value */
    public String wireValue() {
        return Integer.toString(intervalSeconds);
    }
}
