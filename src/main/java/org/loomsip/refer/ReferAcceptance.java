package org.loomsip.refer;

import java.util.Objects;

/** Application decision for one parsed REFER request. */
public record ReferAcceptance(int statusCode, String reasonPhrase) {

    /** Validates an RFC 3261 final response code and safe reason phrase. */
    public ReferAcceptance {
        if (statusCode < 200 || statusCode > 699) {
            throw new IllegalArgumentException("REFER response must be a final SIP status");
        }
        Objects.requireNonNull(reasonPhrase, "reasonPhrase");
        if (reasonPhrase.isBlank() || reasonPhrase.chars().anyMatch(character -> character == '\r' || character == '\n'
                || Character.isISOControl(character))) {
            throw new IllegalArgumentException("REFER reason phrase must not be blank or contain controls");
        }
    }

    /**
     * Reports whether the transfer application accepted the REFER request.
     *
     * @return whether the status code is 2xx
     */
    public boolean accepted() {
        return statusCode >= 200 && statusCode < 300;
    }
}
