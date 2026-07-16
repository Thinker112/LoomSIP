package org.loomsip.message.header;

import java.util.Locale;
import java.util.Objects;

/**
 * Normalized host and optional port from a Via sent-by value.
 *
 * @param host lowercase hostname or IP literal without IPv6 brackets
 * @param port explicit port, or zero when the Via field omitted it
 */
public record SentBy(String host, int port) {

    /**
     * Validates and creates a sent-by value.
     *
     * @throws NullPointerException if {@code host} is {@code null}
     * @throws IllegalArgumentException if the host is blank or the port is invalid
     */
    public SentBy {
        Objects.requireNonNull(host, "host");
        host = host.toLowerCase(Locale.ROOT);
        if (host.isBlank() || host.chars().anyMatch(Character::isWhitespace)) {
            throw new IllegalArgumentException("sent-by host must not be blank or contain whitespace");
        }
        if (port < 0 || port > 65_535) {
            throw new IllegalArgumentException("sent-by port must be zero or between 1 and 65535");
        }
    }

    /**
     * Resolves an omitted port using the Via transport default.
     *
     * @param transport Via transport token
     * @return explicit or default port
     */
    public int effectivePort(ViaTransport transport) {
        Objects.requireNonNull(transport, "transport");
        return port == 0 ? transport.defaultPort() : port;
    }

    @Override
    public String toString() {
        String formattedHost = host.indexOf(':') >= 0 ? "[" + host + "]" : host;
        return port == 0 ? formattedHost : formattedHost + ":" + port;
    }
}
