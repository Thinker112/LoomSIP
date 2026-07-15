package org.loomsip.message;

import java.util.Objects;
import java.util.regex.Pattern;

public record SipVersion(String value) {

    private static final Pattern VERSION_PATTERN = Pattern.compile("SIP/\\d+\\.\\d+");

    public static final SipVersion SIP_2_0 = new SipVersion("SIP/2.0");

    public SipVersion {
        Objects.requireNonNull(value, "value");
        if (!VERSION_PATTERN.matcher(value).matches()) {
            throw new IllegalArgumentException("invalid SIP version: " + value);
        }
    }

    public static SipVersion of(String value) {
        return SIP_2_0.value.equals(value) ? SIP_2_0 : new SipVersion(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
