package org.loomsip.message.header;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * Typed view of one Via value used for transaction routing.
 *
 * @param transport sent-protocol transport token
 * @param sentBy normalized sent-by host and optional port
 * @param parameters immutable ordered Via parameters
 */
public record ViaHeaderValue(
        ViaTransport transport,
        SentBy sentBy,
        List<SipParameter> parameters
) {

    /** RFC 3261 branch magic cookie. */
    public static final String MAGIC_COOKIE = "z9hG4bK";

    /**
     * Creates an immutable Via value.
     *
     * @throws NullPointerException if a component is {@code null}
     */
    public ViaHeaderValue {
        Objects.requireNonNull(transport, "transport");
        Objects.requireNonNull(sentBy, "sentBy");
        parameters = List.copyOf(parameters);
    }

    /**
     * Returns the first value of a named Via parameter.
     *
     * @param name case-insensitive parameter name
     * @return decoded parameter value; flag parameters return an empty optional
     */
    public Optional<String> parameter(String name) {
        String normalized = Objects.requireNonNull(name, "name").toLowerCase(Locale.ROOT);
        return parameters.stream()
                .filter(parameter -> parameter.name().equals(normalized))
                .findFirst()
                .flatMap(SipParameter::value);
    }

    /**
     * Tests whether the parameter is present, including flag parameters.
     *
     * @param name case-insensitive parameter name
     * @return {@code true} when present
     */
    public boolean hasParameter(String name) {
        String normalized = Objects.requireNonNull(name, "name").toLowerCase(Locale.ROOT);
        return parameters.stream().anyMatch(parameter -> parameter.name().equals(normalized));
    }

    /**
     * Returns the Via branch value.
     *
     * @return branch parameter, or empty when absent
     */
    public Optional<String> branch() {
        return parameter("branch");
    }

    /**
     * Indicates whether branch starts with the RFC 3261 magic cookie.
     *
     * @return {@code true} for an RFC 3261 branch
     */
    public boolean hasMagicCookie() {
        return branch().filter(value -> value.regionMatches(true, 0, MAGIC_COOKIE, 0, MAGIC_COOKIE.length()))
                .isPresent();
    }

    /**
     * Returns the received parameter when present with a value.
     *
     * @return received host value
     */
    public Optional<String> received() {
        return parameter("received");
    }

    /**
     * Indicates whether rport was present, including its flag form.
     *
     * @return {@code true} when rport exists
     */
    public boolean hasRPort() {
        return hasParameter("rport");
    }

    /**
     * Returns the numeric rport value when one was supplied.
     *
     * @return numeric rport, or empty for absent/flag-form rport
     * @throws IllegalStateException if rport contains a non-numeric or invalid port
     */
    public OptionalInt rport() {
        Optional<String> value = parameter("rport");
        if (value.isEmpty()) {
            return OptionalInt.empty();
        }
        try {
            int parsed = Integer.parseInt(value.orElseThrow());
            if (parsed < 1 || parsed > 65_535) {
                throw new IllegalStateException("rport must be between 1 and 65535");
            }
            return OptionalInt.of(parsed);
        } catch (NumberFormatException exception) {
            throw new IllegalStateException("rport must be numeric", exception);
        }
    }

    /**
     * Renders this Via value for a SIP header field.
     *
     * @return normalized wire value
     */
    public String wireValue() {
        StringBuilder value = new StringBuilder("SIP/2.0/")
                .append(transport.value())
                .append(' ')
                .append(sentBy);
        for (SipParameter parameter : parameters) {
            value.append(';').append(parameter.name());
            parameter.value().ifPresent(parameterValue -> value.append('=')
                    .append(renderParameter(parameterValue)));
        }
        return value.toString();
    }

    private static String renderParameter(String value) {
        try {
            HeaderSyntax.requireToken(value, "Via parameter value");
            return value;
        } catch (IllegalArgumentException ignored) {
            return '"' + value.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
        }
    }
}
