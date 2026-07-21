package org.loomsip.refer;

import org.loomsip.message.SipBody;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * RFC 3515 {@code message/sipfrag} status-line payload for a refer NOTIFY.
 *
 * <pre>{@code
 * transfer progress --> SIP/2.0 status line --> NOTIFY message/sipfrag body
 * }</pre>
 *
 * @param statusCode SIP response code describing referred-request progress
 * @param reasonPhrase status text without CR/LF characters
 */
public record SipfragStatus(int statusCode, String reasonPhrase) {

    /** Validates a SIP response code and safe reason phrase. */
    public SipfragStatus {
        if (statusCode < 100 || statusCode > 699) {
            throw new IllegalArgumentException("sipfrag status code must be between 100 and 699");
        }
        Objects.requireNonNull(reasonPhrase, "reasonPhrase");
        if (reasonPhrase.isBlank() || reasonPhrase.chars().anyMatch(character -> character == '\r' || character == '\n'
                || Character.isISOControl(character))) {
            throw new IllegalArgumentException("sipfrag reason phrase must not be blank or contain controls");
        }
    }

    /**
     * Parses the first status line in a SIP-fragment body.
     *
     * @param body message/sipfrag body
     * @return parsed response status
     * @throws IllegalArgumentException if the body has no valid SIP/2.0 status line
     */
    public static SipfragStatus parse(SipBody body) {
        Objects.requireNonNull(body, "body");
        String text = new String(body.bytes(), StandardCharsets.US_ASCII);
        int end = text.indexOf("\r\n");
        if (end < 0) {
            throw new IllegalArgumentException("sipfrag must contain a CRLF-terminated status line");
        }
        String[] parts = text.substring(0, end).split(" ", 3);
        if (parts.length != 3 || !"SIP/2.0".equals(parts[0]) || !parts[1].matches("[1-6][0-9]{2}")) {
            throw new IllegalArgumentException("invalid sipfrag status line");
        }
        return new SipfragStatus(Integer.parseInt(parts[1]), parts[2]);
    }

    /**
     * Encodes this status as a minimal CRLF-terminated message/sipfrag body.
     *
     * @return immutable ASCII SIP-fragment body
     */
    public SipBody toBody() {
        return SipBody.of(("SIP/2.0 " + statusCode + " " + reasonPhrase + "\r\n").getBytes(StandardCharsets.US_ASCII));
    }

    /**
     * Reports whether the referred operation has reached a final response class.
     *
     * @return whether the status is 2xx through 6xx
     */
    public boolean isFinal() {
        return statusCode >= 200;
    }
}
