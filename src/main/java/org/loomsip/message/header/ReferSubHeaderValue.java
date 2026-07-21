package org.loomsip.message.header;

import java.util.Locale;

/**
 * RFC 4488 Refer-Sub preference controlling the implicit refer subscription.
 *
 * <pre>{@code
 * absent Refer-Sub --> true (implicit subscription enabled)
 * Refer-Sub:false --> no implicit refer subscription
 * }</pre>
 *
 * @param enabled whether the referrer requests implicit subscription updates
 */
public record ReferSubHeaderValue(boolean enabled) {

    /** Default RFC 3515 behavior when Refer-Sub is absent. */
    public static final ReferSubHeaderValue DEFAULT = new ReferSubHeaderValue(true);

    /**
     * Parses an RFC 4488 boolean token.
     *
     * @param value raw Refer-Sub field value
     * @return structured subscription preference
     * @throws IllegalArgumentException if the value is neither true nor false
     */
    public static ReferSubHeaderValue parse(String value) {
        String normalized = java.util.Objects.requireNonNull(value, "value").strip().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "true" -> DEFAULT;
            case "false" -> new ReferSubHeaderValue(false);
            default -> throw new IllegalArgumentException("Refer-Sub must be true or false");
        };
    }

    /**
     * Renders the canonical RFC 4488 boolean token.
     *
     * @return true or false
     */
    public String wireValue() {
        return Boolean.toString(enabled);
    }
}
