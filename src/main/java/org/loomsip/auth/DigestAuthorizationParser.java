package org.loomsip.auth;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/** Parses one client {@code Authorization: Digest ...} header value. */
public final class DigestAuthorizationParser {

    /** Creates a stateless authorization parser. */
    public DigestAuthorizationParser() {
    }

    /**
     * Tests whether a header value begins with the Digest authentication scheme.
     *
     * @param headerValue complete Authorization header value
     * @return {@code true} when the value begins with {@code Digest} followed by whitespace
     */
    public boolean isDigestAuthorization(String headerValue) {
        String value = headerValue == null ? "" : headerValue.stripLeading();
        return value.length() > 6
                && value.regionMatches(true, 0, "Digest", 0, 6)
                && isWhitespace(value.charAt(6));
    }

    /**
     * Parses one supported qop-auth Digest authorization value.
     *
     * @param headerValue complete Digest Authorization header value
     * @return immutable parsed authorization parameters
     * @throws DigestChallengeParseException if syntax or required fields are invalid
     * @throws DigestUnsupportedChallengeException if algorithm or qop is unsupported
     */
    public DigestAuthorizationRequest parse(String headerValue) {
        if (!isDigestAuthorization(headerValue)) {
            throw new DigestChallengeParseException("header value is not a Digest authorization");
        }
        Map<String, String> parameters = parseParameters(headerValue.stripLeading().substring(6));
        String qop = required(parameters, "qop");
        if (!DigestQop.AUTH.wireName().equalsIgnoreCase(qop)) {
            throw new DigestUnsupportedChallengeException("unsupported Digest qop: " + qop);
        }
        return new DigestAuthorizationRequest(
                required(parameters, "username"),
                required(parameters, "realm"),
                required(parameters, "nonce"),
                required(parameters, "uri"),
                required(parameters, "response"),
                DigestAlgorithm.parse(parameters.getOrDefault("algorithm", "MD5")),
                DigestQop.AUTH,
                required(parameters, "nc"),
                required(parameters, "cnonce"),
                Optional.ofNullable(parameters.get("opaque"))
        );
    }

    private static Map<String, String> parseParameters(String text) {
        Map<String, String> parameters = new LinkedHashMap<>();
        int index = 0;
        while (true) {
            index = skipWhitespace(text, index);
            if (index >= text.length()) {
                break;
            }
            if (text.charAt(index) == ',') {
                index = skipWhitespace(text, index + 1);
                if (index >= text.length()) {
                    throw new DigestChallengeParseException("Digest authorization has a trailing comma");
                }
            }
            int nameStart = index;
            while (index < text.length() && isTokenCharacter(text.charAt(index))) {
                index++;
            }
            if (index == nameStart) {
                throw new DigestChallengeParseException("Digest authorization parameter name is invalid");
            }
            String name = text.substring(nameStart, index).toLowerCase(Locale.ROOT);
            index = skipWhitespace(text, index);
            if (index >= text.length() || text.charAt(index) != '=') {
                throw new DigestChallengeParseException("Digest authorization parameter " + name + " lacks '='");
            }
            index = skipWhitespace(text, index + 1);
            ParsedValue parsed = parseValue(text, index, name);
            if (parameters.putIfAbsent(name, parsed.value()) != null) {
                throw new DigestChallengeParseException("Digest authorization parameter " + name + " is duplicated");
            }
            index = skipWhitespace(text, parsed.nextIndex());
            if (index < text.length() && text.charAt(index) != ',') {
                throw new DigestChallengeParseException(
                        "Digest authorization parameter " + name + " has invalid trailing text"
                );
            }
        }
        return Map.copyOf(parameters);
    }

    private static ParsedValue parseValue(String text, int index, String name) {
        if (index >= text.length()) {
            throw new DigestChallengeParseException("Digest authorization parameter " + name + " has no value");
        }
        if (text.charAt(index) != '"') {
            int end = index;
            while (end < text.length() && text.charAt(end) != ',' && !isWhitespace(text.charAt(end))) {
                end++;
            }
            if (end == index) {
                throw new DigestChallengeParseException("Digest authorization parameter " + name + " has no value");
            }
            return new ParsedValue(text.substring(index, end), end);
        }
        StringBuilder value = new StringBuilder();
        boolean escaped = false;
        int position = index + 1;
        for (; position < text.length(); position++) {
            char character = text.charAt(position);
            if (escaped) {
                value.append(character);
                escaped = false;
            } else if (character == '\\') {
                escaped = true;
            } else if (character == '"') {
                return new ParsedValue(value.toString(), position + 1);
            } else if (character == '\r' || character == '\n') {
                throw new DigestChallengeParseException(
                        "Digest authorization parameter " + name + " contains a line break"
                );
            } else {
                value.append(character);
            }
        }
        throw new DigestChallengeParseException("Digest authorization parameter " + name + " has an unterminated quote");
    }

    private static String required(Map<String, String> parameters, String name) {
        String value = parameters.get(name);
        if (value == null || value.isBlank()) {
            throw new DigestChallengeParseException("Digest authorization lacks " + name);
        }
        return value;
    }

    private static int skipWhitespace(String text, int index) {
        while (index < text.length() && isWhitespace(text.charAt(index))) {
            index++;
        }
        return index;
    }

    private static boolean isWhitespace(char character) {
        return character == ' ' || character == '\t';
    }

    private static boolean isTokenCharacter(char character) {
        return character > 32 && character < 127 && "()<>@,;:\\\"/[]?={}".indexOf(character) < 0;
    }

    private record ParsedValue(String value, int nextIndex) {
    }
}
