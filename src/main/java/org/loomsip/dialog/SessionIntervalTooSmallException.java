package org.loomsip.dialog;

/** Indicates that a requested Session-Expires interval requires a 422 response. */
public final class SessionIntervalTooSmallException extends IllegalArgumentException {

    private final int minimumSeconds;

    /**
     * Creates a 422 interval failure.
     *
     * @param minimumSeconds required Min-SE value
     */
    public SessionIntervalTooSmallException(int minimumSeconds) {
        super("session interval is below Min-SE");
        this.minimumSeconds = minimumSeconds;
    }

    /** @return Min-SE value for the 422 response */
    public int minimumSeconds() {
        return minimumSeconds;
    }
}
