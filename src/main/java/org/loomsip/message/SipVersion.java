package org.loomsip.message;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * SIP protocol version token from a request-line or status-line.
 *
 * @param value version in {@code SIP/major.minor} form
 */
public record SipVersion(String value) {

    private static final Pattern VERSION_PATTERN = Pattern.compile("SIP/\\d+\\.\\d+");

    /** Standard SIP version defined by RFC 3261. */
    public static final SipVersion SIP_2_0 = new SipVersion("SIP/2.0");

    /**
     * Validates and creates a protocol version.
     *
     * @throws NullPointerException if {@code value} is {@code null}
     * @throws IllegalArgumentException if the value is not in SIP version form
     */
    public SipVersion {
        Objects.requireNonNull(value, "value");
        if (!VERSION_PATTERN.matcher(value).matches()) {
            throw new IllegalArgumentException("invalid SIP version: " + value);
        }
    }

    /**
     * Returns the shared SIP/2.0 constant when applicable, or a new version value.
     *
     * @param value version in {@code SIP/major.minor} form
     * @return protocol version value
     * @throws NullPointerException if {@code value} is {@code null}
     * @throws IllegalArgumentException if the version is malformed
     */
    public static SipVersion of(String value) {
        return SIP_2_0.value.equals(value) ? SIP_2_0 : new SipVersion(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
