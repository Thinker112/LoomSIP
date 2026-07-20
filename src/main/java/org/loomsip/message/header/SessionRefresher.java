package org.loomsip.message.header;

/** RFC 4028 session refresher parameter values. */
public enum SessionRefresher {
    /** User agent client refreshes the session. */
    UAC("uac"),
    /** User agent server refreshes the session. */
    UAS("uas");

    private final String wireValue;

    SessionRefresher(String wireValue) {
        this.wireValue = wireValue;
    }

    /** @return RFC 4028 parameter value */
    public String wireValue() {
        return wireValue;
    }

    /**
     * Parses a refresher token.
     *
     * @param value token value
     * @return refresher role
     */
    public static SessionRefresher parse(String value) {
        return switch (value.toLowerCase(java.util.Locale.ROOT)) {
            case "uac" -> UAC;
            case "uas" -> UAS;
            default -> throw new IllegalArgumentException("invalid session refresher");
        };
    }
}
