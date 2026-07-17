package org.loomsip.message.header;

import org.loomsip.message.SipUri;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * Typed SIP name-addr or addr-spec value with parameters outside the URI.
 *
 * @param displayName decoded display name
 * @param uri complete URI including URI parameters
 * @param parameters ordered header parameters following the name-addr
 */
public record AddressHeaderValue(
        Optional<String> displayName,
        SipUri uri,
        List<SipParameter> parameters
) {

    /** Validates and defensively copies the address value. */
    public AddressHeaderValue {
        Objects.requireNonNull(displayName, "displayName");
        Objects.requireNonNull(uri, "uri");
        parameters = List.copyOf(Objects.requireNonNull(parameters, "parameters"));
        displayName.ifPresent(value -> {
            if (value.isBlank() || value.chars().anyMatch(Character::isISOControl)) {
                throw new IllegalArgumentException("display name must not be blank or contain controls");
            }
        });
    }

    /**
     * Returns the first external header parameter with the requested name.
     *
     * @param name case-insensitive parameter name
     * @return matching parameter
     */
    public Optional<SipParameter> parameter(String name) {
        String normalized = Objects.requireNonNull(name, "name").toLowerCase(Locale.ROOT);
        return parameters.stream().filter(parameter -> parameter.name().equals(normalized)).findFirst();
    }

    /**
     * Tests for a flag or valued parameter embedded in the URI.
     *
     * @param name case-insensitive URI parameter name
     * @return whether the URI contains the parameter
     */
    public boolean hasUriParameter(String name) {
        String expected = Objects.requireNonNull(name, "name").toLowerCase(Locale.ROOT);
        String value = uri.value();
        int query = value.indexOf('?');
        int end = query < 0 ? value.length() : query;
        int position = value.indexOf(';');
        while (position >= 0 && position < end) {
            int next = value.indexOf(';', position + 1);
            int parameterEnd = next < 0 || next > end ? end : next;
            int equals = value.indexOf('=', position + 1);
            int nameEnd = equals >= 0 && equals < parameterEnd ? equals : parameterEnd;
            if (value.substring(position + 1, nameEnd).equalsIgnoreCase(expected)) {
                return true;
            }
            position = next;
        }
        return false;
    }

    /**
     * Renders a normalized name-addr suitable for a SIP header field.
     *
     * @return normalized wire value
     */
    public String wireValue() {
        StringBuilder value = new StringBuilder();
        displayName.ifPresent(name -> value.append('"')
                .append(name.replace("\\", "\\\\").replace("\"", "\\\""))
                .append("\" "));
        value.append('<').append(uri.value()).append('>');
        for (SipParameter parameter : parameters) {
            value.append(';').append(parameter.name());
            parameter.value().ifPresent(parameterValue -> value.append('=').append(renderParameter(parameterValue)));
        }
        return value.toString();
    }

    private static String renderParameter(String value) {
        try {
            HeaderSyntax.requireToken(value, "parameter value");
            return value;
        } catch (IllegalArgumentException ignored) {
            return '"' + value.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
        }
    }
}
