package org.loomsip.message.header;

import java.util.Locale;
import java.util.Objects;

/**
 * Extensible transport token from the sent-protocol part of a Via field.
 *
 * @param value uppercase transport token
 */
public record ViaTransport(String value) {

    /** Standard UDP Via transport. */
    public static final ViaTransport UDP = new ViaTransport("UDP");
    /** Standard TCP Via transport. */
    public static final ViaTransport TCP = new ViaTransport("TCP");
    /** Standard TLS Via transport. */
    public static final ViaTransport TLS = new ViaTransport("TLS");

    /**
     * Validates and creates a Via transport token.
     *
     * @throws NullPointerException if {@code value} is {@code null}
     * @throws IllegalArgumentException if the value is not a valid token
     */
    public ViaTransport {
        Objects.requireNonNull(value, "value");
        value = value.toUpperCase(Locale.ROOT);
        HeaderSyntax.requireToken(value, "Via transport");
    }

    /**
     * Returns a shared standard value when available.
     *
     * @param value Via transport token
     * @return normalized transport value
     */
    public static ViaTransport of(String value) {
        String normalized = Objects.requireNonNull(value, "value").toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "UDP" -> UDP;
            case "TCP" -> TCP;
            case "TLS" -> TLS;
            default -> new ViaTransport(normalized);
        };
    }

    /**
     * Returns the default SIP port used when sent-by omits a port.
     *
     * @return 5061 for TLS and 5060 for other currently supported SIP transports
     */
    public int defaultPort() {
        return TLS.equals(this) ? 5061 : 5060;
    }

    @Override
    public String toString() {
        return value;
    }
}
