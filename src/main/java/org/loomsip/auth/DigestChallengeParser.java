package org.loomsip.auth;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Parses one {@code Digest} WWW-Authenticate or Proxy-Authenticate value. */
public final class DigestChallengeParser {

    /** Creates a stateless Digest challenge parser. */
    public DigestChallengeParser() {
    }

    /**
     * Tests whether a header value begins with the Digest authentication scheme.
     *
     * @param headerValue complete header value
     * @return {@code true} when the value begins with {@code Digest} followed by whitespace
     */
    public boolean isDigestChallenge(String headerValue) {
        String value = headerValue == null ? "" : headerValue.stripLeading();
        return value.length() > 6
                && value.regionMatches(true, 0, "Digest", 0, 6)
                && isWhitespace(value.charAt(6));
    }

    /**
     * Parses one supported Digest challenge.
     *
     * <p>Multiple challenges should be carried as separate SIP header fields;
     * callers select one parsed challenge according to local policy.</p>
     *
     * @param headerValue complete Digest challenge value
     * @return immutable parsed challenge
     * @throws DigestChallengeParseException if syntax or required fields are invalid
     * @throws DigestUnsupportedChallengeException if algorithm or charset is unsupported
     */
    public DigestChallenge parse(String headerValue) {
        if (!isDigestChallenge(headerValue)) {
            throw new DigestChallengeParseException("header value is not a Digest challenge");
        }
        Map<String, String> parameters = parseParameters(headerValue.stripLeading().substring(6));
        String realm = required(parameters, "realm");
        String nonce = required(parameters, "nonce");
        DigestAlgorithm algorithm = DigestAlgorithm.parse(parameters.getOrDefault("algorithm", "MD5"));
        Set<String> qops = parseQops(parameters.get("qop"));
        boolean stale = parseBoolean(parameters.getOrDefault("stale", "false"), "stale");
        DigestCharset charset = parameters.containsKey("charset")
                ? DigestCharset.parse(parameters.get("charset"))
                : DigestCharset.ISO_8859_1;
        return new DigestChallenge(
                realm,
                nonce,
                Optional.ofNullable(parameters.get("opaque")),
                algorithm,
                qops,
                stale,
                charset
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
                    throw new DigestChallengeParseException("Digest challenge has a trailing comma");
                }
            }
            int nameStart = index;
            while (index < text.length() && isTokenCharacter(text.charAt(index))) {
                index++;
            }
            if (index == nameStart) {
                throw new DigestChallengeParseException("Digest challenge parameter name is invalid");
            }
            String name = text.substring(nameStart, index).toLowerCase(Locale.ROOT);
            index = skipWhitespace(text, index);
            if (index >= text.length() || text.charAt(index) != '=') {
                throw new DigestChallengeParseException("Digest parameter " + name + " lacks '='");
            }
            index = skipWhitespace(text, index + 1);
            ParsedValue parsed = parseValue(text, index, name);
            if (parameters.putIfAbsent(name, parsed.value()) != null) {
                throw new DigestChallengeParseException("Digest parameter " + name + " is duplicated");
            }
            index = skipWhitespace(text, parsed.nextIndex());
            if (index < text.length() && text.charAt(index) != ',') {
                throw new DigestChallengeParseException("Digest parameter " + name + " has invalid trailing text");
            }
        }
        return Map.copyOf(parameters);
    }

    private static ParsedValue parseValue(String text, int index, String name) {
        if (index >= text.length()) {
            throw new DigestChallengeParseException("Digest parameter " + name + " has no value");
        }
        if (text.charAt(index) != '"') {
            int end = index;
            while (end < text.length() && text.charAt(end) != ',' && !isWhitespace(text.charAt(end))) {
                end++;
            }
            if (end == index) {
                throw new DigestChallengeParseException("Digest parameter " + name + " has no value");
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
                throw new DigestChallengeParseException("Digest parameter " + name + " contains a line break");
            } else {
                value.append(character);
            }
        }
        throw new DigestChallengeParseException("Digest parameter " + name + " has an unterminated quote");
    }

    private static Set<String> parseQops(String qopValue) {
        if (qopValue == null) {
            return Set.of();
        }
        Set<String> qops = new LinkedHashSet<>();
        for (String value : qopValue.split(",", -1)) {
            String option = value.strip().toLowerCase(Locale.ROOT);
            if (option.isEmpty() || !option.chars().allMatch(character ->
                    isTokenCharacter((char) character))) {
                throw new DigestChallengeParseException("Digest qop option is invalid");
            }
            qops.add(option);
        }
        return Set.copyOf(qops);
    }

    private static boolean parseBoolean(String value, String name) {
        if ("true".equalsIgnoreCase(value)) {
            return true;
        }
        if ("false".equalsIgnoreCase(value)) {
            return false;
        }
        throw new DigestChallengeParseException("Digest parameter " + name + " must be true or false");
    }

    private static String required(Map<String, String> parameters, String name) {
        String value = parameters.get(name);
        if (value == null || value.isBlank()) {
            throw new DigestChallengeParseException("Digest challenge lacks " + name);
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
