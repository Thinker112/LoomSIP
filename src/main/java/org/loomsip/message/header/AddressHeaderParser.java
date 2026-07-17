package org.loomsip.message.header;

import org.loomsip.message.SipUri;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Parser shared by Contact, Route, and Record-Route typed views. */
final class AddressHeaderParser {

    private AddressHeaderParser() {
    }

    static List<String> splitList(String value, String headerName) throws SipHeaderValueException {
        List<String> values = new ArrayList<>();
        boolean quoted = false;
        boolean escaped = false;
        int angleDepth = 0;
        int start = 0;
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (escaped) {
                escaped = false;
            } else if (quoted && character == '\\') {
                escaped = true;
            } else if (character == '"') {
                quoted = !quoted;
            } else if (!quoted && character == '<') {
                angleDepth++;
            } else if (!quoted && character == '>') {
                angleDepth--;
                if (angleDepth < 0) {
                    throw new SipHeaderValueException("unbalanced angle bracket in " + headerName);
                }
            } else if (!quoted && angleDepth == 0 && character == ',') {
                addListValue(values, value.substring(start, index), headerName);
                start = index + 1;
            }
        }
        if (quoted || escaped || angleDepth != 0) {
            throw new SipHeaderValueException("unterminated quoted string or name-addr in " + headerName);
        }
        addListValue(values, value.substring(start), headerName);
        return List.copyOf(values);
    }

    static AddressHeaderValue parseAddress(String text, String headerName) throws SipHeaderValueException {
        String value = text.strip();
        int openingAngle = indexOutsideQuotes(value, '<', 0);
        try {
            if (openingAngle < 0) {
                if (value.indexOf('"') >= 0 || value.indexOf('>') >= 0) {
                    throw new SipHeaderValueException("invalid addr-spec in " + headerName);
                }
                return new AddressHeaderValue(Optional.empty(), SipUri.parse(value), List.of());
            }

            int closingAngle = indexOutsideQuotes(value, '>', openingAngle + 1);
            if (closingAngle < 0 || indexOutsideQuotes(value, '<', openingAngle + 1) >= 0) {
                throw new SipHeaderValueException("unbalanced name-addr in " + headerName);
            }
            String displayText = value.substring(0, openingAngle).strip();
            Optional<String> displayName = displayText.isEmpty()
                    ? Optional.empty()
                    : Optional.of(parseDisplayName(displayText, headerName));
            SipUri uri = SipUri.parse(value.substring(openingAngle + 1, closingAngle).strip());
            List<SipParameter> parameters = parseParameters(
                    value.substring(closingAngle + 1).strip(),
                    headerName
            );
            return new AddressHeaderValue(displayName, uri, parameters);
        } catch (IllegalArgumentException exception) {
            throw new SipHeaderValueException("invalid " + headerName + " address: " + text, exception);
        }
    }

    private static String parseDisplayName(String value, String headerName) throws SipHeaderValueException {
        if (value.charAt(0) != '"') {
            if (value.indexOf('"') >= 0 || value.chars().anyMatch(Character::isISOControl)) {
                throw new SipHeaderValueException("invalid display name in " + headerName);
            }
            return value;
        }
        if (value.length() < 2 || value.charAt(value.length() - 1) != '"') {
            throw new SipHeaderValueException("unterminated display name in " + headerName);
        }
        StringBuilder decoded = new StringBuilder();
        boolean escaped = false;
        for (int index = 1; index < value.length() - 1; index++) {
            char character = value.charAt(index);
            if (escaped) {
                decoded.append(character);
                escaped = false;
            } else if (character == '\\') {
                escaped = true;
            } else if (character == '"') {
                throw new SipHeaderValueException("unescaped quote in " + headerName + " display name");
            } else {
                decoded.append(character);
            }
        }
        if (escaped) {
            throw new SipHeaderValueException("unterminated escape in " + headerName + " display name");
        }
        return decoded.toString();
    }

    private static List<SipParameter> parseParameters(String text, String headerName)
            throws SipHeaderValueException {
        if (text.isEmpty()) {
            return List.of();
        }
        List<SipParameter> parameters = new ArrayList<>();
        int position = 0;
        while (position < text.length()) {
            if (text.charAt(position) != ';') {
                throw new SipHeaderValueException(headerName + " parameter must start with ';'");
            }
            position++;
            while (position < text.length() && isWhitespace(text.charAt(position))) {
                position++;
            }
            int nameStart = position;
            while (position < text.length()) {
                char character = text.charAt(position);
                if (character == '=' || character == ';' || isWhitespace(character)) {
                    break;
                }
                position++;
            }
            if (position == nameStart) {
                throw new SipHeaderValueException(headerName + " contains an empty parameter name");
            }
            String name = text.substring(nameStart, position);
            while (position < text.length() && isWhitespace(text.charAt(position))) {
                position++;
            }
            Optional<String> parameterValue = Optional.empty();
            if (position < text.length() && text.charAt(position) == '=') {
                position++;
                while (position < text.length() && isWhitespace(text.charAt(position))) {
                    position++;
                }
                ParsedValue parsed = parseParameterValue(text, position, headerName);
                parameterValue = Optional.of(parsed.value());
                position = parsed.nextPosition();
            }
            try {
                parameters.add(new SipParameter(name, parameterValue));
            } catch (IllegalArgumentException exception) {
                throw new SipHeaderValueException("invalid " + headerName + " parameter", exception);
            }
            while (position < text.length() && isWhitespace(text.charAt(position))) {
                position++;
            }
        }
        return List.copyOf(parameters);
    }

    private static ParsedValue parseParameterValue(String text, int position, String headerName)
            throws SipHeaderValueException {
        if (position >= text.length()) {
            throw new SipHeaderValueException(headerName + " parameter value is empty");
        }
        if (text.charAt(position) != '"') {
            int end = position;
            while (end < text.length() && text.charAt(end) != ';') {
                end++;
            }
            String value = text.substring(position, end).stripTrailing();
            if (value.isEmpty()) {
                throw new SipHeaderValueException(headerName + " parameter value is empty");
            }
            return new ParsedValue(value, end);
        }
        StringBuilder decoded = new StringBuilder();
        boolean escaped = false;
        int index = position + 1;
        for (; index < text.length(); index++) {
            char character = text.charAt(index);
            if (escaped) {
                decoded.append(character);
                escaped = false;
            } else if (character == '\\') {
                escaped = true;
            } else if (character == '"') {
                index++;
                break;
            } else {
                decoded.append(character);
            }
        }
        if (escaped || index > text.length() || text.charAt(index - 1) != '"') {
            throw new SipHeaderValueException("unterminated quoted parameter in " + headerName);
        }
        return new ParsedValue(decoded.toString(), index);
    }

    private static int indexOutsideQuotes(String value, char searched, int start) {
        boolean quoted = false;
        boolean escaped = false;
        for (int index = start; index < value.length(); index++) {
            char character = value.charAt(index);
            if (escaped) {
                escaped = false;
            } else if (quoted && character == '\\') {
                escaped = true;
            } else if (character == '"') {
                quoted = !quoted;
            } else if (!quoted && character == searched) {
                return index;
            }
        }
        return -1;
    }

    private static void addListValue(List<String> values, String candidate, String headerName)
            throws SipHeaderValueException {
        String stripped = candidate.strip();
        if (stripped.isEmpty()) {
            throw new SipHeaderValueException(headerName + " contains an empty list value");
        }
        values.add(stripped);
    }

    private static boolean isWhitespace(char character) {
        return character == ' ' || character == '\t';
    }

    private record ParsedValue(String value, int nextPosition) {
    }
}
