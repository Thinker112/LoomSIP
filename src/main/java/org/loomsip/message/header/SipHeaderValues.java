package org.loomsip.message.header;

import org.loomsip.message.SipHeader;
import org.loomsip.message.SipHeaders;
import org.loomsip.message.SipMethod;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * Parses typed transaction-routing values from generic immutable SIP headers.
 *
 * <pre>{@code
 * SipHeaders (lossless/raw)
 *           |
 *           v
 * +-------------------+
 * | SipHeaderValues   |
 * +----+----+----+----+
 *      |    |    |
 *      |    |    +------> From/To tag and Call-ID
 *      |    +-----------> CSeqHeaderValue
 *      +----------------> ViaHeaderValue
 *                            |
 *                            v
 *                     TransactionKeyFactory
 * }</pre>
 */
public final class SipHeaderValues {

    private SipHeaderValues() {
    }

    /**
     * Parses the first Via value from the first Via field.
     *
     * @param headers message headers
     * @return typed top Via value
     * @throws SipHeaderValueException if Via is missing or malformed
     */
    public static ViaHeaderValue topVia(SipHeaders headers) throws SipHeaderValueException {
        return parseVia(topViaRawValue(headers));
    }

    /**
     * Returns the unfolded raw top Via value without later comma-list entries.
     *
     * @param headers message headers
     * @return first Via value, stripped of surrounding whitespace
     * @throws SipHeaderValueException if Via is missing or has malformed quoting
     */
    public static String topViaRawValue(SipHeaders headers) throws SipHeaderValueException {
        Objects.requireNonNull(headers, "headers");
        SipHeader header = headers.first("Via").orElseThrow(
                () -> new SipHeaderValueException("missing Via header")
        );
        return firstListValue(header.value()).strip();
    }

    /**
     * Parses the single CSeq field.
     *
     * @param headers message headers
     * @return typed CSeq value
     * @throws SipHeaderValueException if CSeq is missing, duplicated, or malformed
     */
    public static CSeqHeaderValue cseq(SipHeaders headers) throws SipHeaderValueException {
        String value = requiredSingleValue(headers, "CSeq");
        String[] components = value.strip().split("[ \\t]+", -1);
        if (components.length != 2) {
            throw new SipHeaderValueException("CSeq must contain a sequence number and method");
        }
        try {
            if (!isAsciiDigits(components[0])) {
                throw new NumberFormatException("non-ASCII CSeq digits");
            }
            long sequence = Long.parseLong(components[0]);
            return new CSeqHeaderValue(sequence, SipMethod.of(components[1]));
        } catch (IllegalArgumentException exception) {
            throw new SipHeaderValueException("invalid CSeq header: " + value, exception);
        }
    }

    /**
     * Parses the From tag.
     *
     * @param headers message headers
     * @return tag value, or empty when From has no tag
     * @throws SipHeaderValueException if From is missing, duplicated, or malformed
     */
    public static Optional<String> fromTag(SipHeaders headers) throws SipHeaderValueException {
        return addressParameter(requiredSingleValue(headers, "From"), "tag", "From");
    }

    /**
     * Parses the To tag.
     *
     * @param headers message headers
     * @return tag value, or empty when To has no tag
     * @throws SipHeaderValueException if To is missing, duplicated, or malformed
     */
    public static Optional<String> toTag(SipHeaders headers) throws SipHeaderValueException {
        return addressParameter(requiredSingleValue(headers, "To"), "tag", "To");
    }

    /**
     * Returns the validated single Call-ID value.
     *
     * @param headers message headers
     * @return complete Call-ID value
     * @throws SipHeaderValueException if Call-ID is missing, duplicated, empty, or contains whitespace
     */
    public static String callId(SipHeaders headers) throws SipHeaderValueException {
        String value = requiredSingleValue(headers, "Call-ID").strip();
        if (value.isEmpty() || value.chars().anyMatch(Character::isWhitespace)) {
            throw new SipHeaderValueException("Call-ID must not be empty or contain whitespace");
        }
        return value;
    }

    /**
     * Parses the single RFC 3262 RSeq header.
     *
     * @param headers SIP message headers
     * @return typed RSeq value
     * @throws SipHeaderValueException if RSeq is missing, duplicated, or malformed
     */
    public static RSeqHeaderValue rseq(SipHeaders headers) throws SipHeaderValueException {
        String value = requiredSingleValue(headers, "RSeq").strip();
        try {
            if (!isAsciiDigits(value)) {
                throw new NumberFormatException("non-ASCII RSeq digits");
            }
            return new RSeqHeaderValue(Long.parseLong(value));
        } catch (IllegalArgumentException exception) {
            throw new SipHeaderValueException("invalid RSeq header", exception);
        }
    }

    /**
     * Parses the single RFC 3262 RAck header.
     *
     * @param headers SIP message headers
     * @return typed RAck correlation value
     * @throws SipHeaderValueException if RAck is missing, duplicated, or malformed
     */
    public static RAckHeaderValue rack(SipHeaders headers) throws SipHeaderValueException {
        String value = requiredSingleValue(headers, "RAck").strip();
        String[] components = value.split("[ \\t]+", -1);
        if (components.length != 3) {
            throw new SipHeaderValueException("RAck must contain RSeq, CSeq, and method");
        }
        try {
            if (!isAsciiDigits(components[0]) || !isAsciiDigits(components[1])) {
                throw new NumberFormatException("non-ASCII RAck sequence digits");
            }
            return new RAckHeaderValue(
                    Long.parseLong(components[0]),
                    Long.parseLong(components[1]),
                    SipMethod.of(components[2])
            );
        } catch (IllegalArgumentException exception) {
            throw new SipHeaderValueException("invalid RAck header", exception);
        }
    }

    /** Parses one RFC 4028 Session-Expires header. */
    public static SessionExpiresHeaderValue sessionExpires(SipHeaders headers) throws SipHeaderValueException {
        String value = requiredSingleValue(headers, "Session-Expires").strip();
        String[] parts = value.split(";", -1);
        try {
            if (!isAsciiDigits(parts[0].strip())) {
                throw new NumberFormatException("non-ASCII session interval");
            }
            java.util.Optional<SessionRefresher> refresher = java.util.Optional.empty();
            for (int index = 1; index < parts.length; index++) {
                String[] parameter = parts[index].strip().split("=", 2);
                if (parameter.length != 2 || parameter[0].strip().isEmpty()) {
                    throw new IllegalArgumentException("invalid Session-Expires parameter");
                }
                if ("refresher".equalsIgnoreCase(parameter[0].strip())) {
                    if (refresher.isPresent()) {
                        throw new IllegalArgumentException("duplicate Session-Expires refresher");
                    }
                    refresher = java.util.Optional.of(SessionRefresher.parse(parameter[1].strip()));
                }
            }
            return new SessionExpiresHeaderValue(Integer.parseInt(parts[0].strip()), refresher);
        } catch (IllegalArgumentException exception) {
            throw new SipHeaderValueException("invalid Session-Expires header", exception);
        }
    }

    /** Parses one RFC 4028 Min-SE header. */
    public static MinSeHeaderValue minSe(SipHeaders headers) throws SipHeaderValueException {
        String value = requiredSingleValue(headers, "Min-SE").strip();
        try {
            if (!isAsciiDigits(value)) {
                throw new NumberFormatException("non-ASCII Min-SE interval");
            }
            return new MinSeHeaderValue(Integer.parseInt(value));
        } catch (IllegalArgumentException exception) {
            throw new SipHeaderValueException("invalid Min-SE header", exception);
        }
    }

    /**
     * Parses one RFC 6086 Info-Package header.
     *
     * @param headers SIP message headers
     * @return typed INFO package token
     * @throws SipHeaderValueException if Info-Package is missing, duplicated, or malformed
     */
    public static InfoPackageHeaderValue infoPackage(SipHeaders headers) throws SipHeaderValueException {
        String value = requiredSingleValue(headers, "Info-Package").strip();
        try {
            return new InfoPackageHeaderValue(value);
        } catch (IllegalArgumentException exception) {
            throw new SipHeaderValueException("invalid Info-Package header", exception);
        }
    }

    /**
     * Parses all RFC 6086 Recv-Info fields as one ordered capability list.
     *
     * @param headers SIP message headers
     * @return typed advertised INFO packages
     * @throws SipHeaderValueException if Recv-Info is missing, empty, duplicated, or malformed
     */
    public static RecvInfoHeaderValue recvInfo(SipHeaders headers) throws SipHeaderValueException {
        Objects.requireNonNull(headers, "headers");
        List<InfoPackageHeaderValue> packages = new ArrayList<>();
        List<SipHeader> fields = headers.all("Recv-Info");
        if (fields.isEmpty()) {
            throw new SipHeaderValueException("missing Recv-Info header");
        }
        try {
            for (SipHeader field : fields) {
                for (String item : field.value().split(",", -1)) {
                    packages.add(new InfoPackageHeaderValue(item.strip()));
                }
            }
            return new RecvInfoHeaderValue(packages);
        } catch (IllegalArgumentException exception) {
            throw new SipHeaderValueException("invalid Recv-Info header", exception);
        }
    }

    private static ViaHeaderValue parseVia(String value) throws SipHeaderValueException {
        String stripped = value.strip();
        int whitespace = firstWhitespace(stripped);
        if (whitespace <= 0) {
            throw new SipHeaderValueException("Via must contain sent-protocol and sent-by");
        }

        String[] sentProtocol = stripped.substring(0, whitespace).split("/", -1);
        if (sentProtocol.length != 3
                || !"SIP".equalsIgnoreCase(sentProtocol[0])
                || !"2.0".equals(sentProtocol[1])) {
            throw new SipHeaderValueException("Via sent-protocol must use SIP/2.0/transport");
        }

        String remainder = stripped.substring(whitespace).stripLeading();
        int parameterStart = indexOfOutsideQuotes(remainder, ';', 0);
        String sentByText = (parameterStart < 0 ? remainder : remainder.substring(0, parameterStart)).strip();
        String parameterText = parameterStart < 0 ? "" : remainder.substring(parameterStart);
        try {
            ViaTransport transport = ViaTransport.of(sentProtocol[2]);
            SentBy sentBy = parseSentBy(sentByText);
            List<SipParameter> parameters = parseParameters(parameterText, "Via");
            return new ViaHeaderValue(transport, sentBy, parameters);
        } catch (IllegalArgumentException exception) {
            throw new SipHeaderValueException("invalid Via header: " + value, exception);
        }
    }

    private static SentBy parseSentBy(String value) {
        if (value.isEmpty()) {
            throw new IllegalArgumentException("sent-by is empty");
        }
        if (value.charAt(0) == '[') {
            int closing = value.indexOf(']');
            if (closing <= 1) {
                throw new IllegalArgumentException("invalid bracketed IPv6 sent-by");
            }
            String host = value.substring(1, closing);
            String suffix = value.substring(closing + 1);
            if (suffix.isEmpty()) {
                return new SentBy(host, 0);
            }
            if (suffix.charAt(0) != ':' || suffix.length() == 1) {
                throw new IllegalArgumentException("invalid sent-by port suffix");
            }
            return new SentBy(host, parsePort(suffix.substring(1)));
        }

        int firstColon = value.indexOf(':');
        int lastColon = value.lastIndexOf(':');
        if (firstColon != lastColon) {
            throw new IllegalArgumentException("IPv6 sent-by must use square brackets");
        }
        if (lastColon < 0) {
            return new SentBy(value, 0);
        }
        return new SentBy(value.substring(0, lastColon), parsePort(value.substring(lastColon + 1)));
    }

    private static int parsePort(String value) {
        if (!isAsciiDigits(value)) {
            throw new IllegalArgumentException("port must contain ASCII digits");
        }
        int port = Integer.parseInt(value);
        if (port < 1 || port > 65_535) {
            throw new IllegalArgumentException("port must be between 1 and 65535");
        }
        return port;
    }

    private static Optional<String> addressParameter(String value, String name, String headerName)
            throws SipHeaderValueException {
        int parameterStart = addressParameterStart(value);
        if (parameterStart < 0) {
            return Optional.empty();
        }
        List<SipParameter> parameters = parseParameters(value.substring(parameterStart), headerName);
        String normalized = name.toLowerCase(Locale.ROOT);
        Optional<SipParameter> parameter = parameters.stream()
                .filter(candidate -> candidate.name().equals(normalized))
                .findFirst();
        if (parameter.isEmpty()) {
            return Optional.empty();
        }
        Optional<String> result = parameter.orElseThrow().value();
        if (result.isEmpty() || result.orElseThrow().isEmpty()) {
            throw new SipHeaderValueException(headerName + " tag parameter must have a value");
        }
        return result;
    }

    private static int addressParameterStart(String value) throws SipHeaderValueException {
        boolean quoted = false;
        boolean escaped = false;
        int openingAngle = -1;
        int closingAngle = -1;
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (escaped) {
                escaped = false;
            } else if (quoted && character == '\\') {
                escaped = true;
            } else if (character == '"') {
                quoted = !quoted;
            } else if (!quoted && character == '<') {
                openingAngle = index;
            } else if (!quoted && character == '>') {
                closingAngle = index;
            }
        }
        if (quoted || escaped) {
            throw new SipHeaderValueException("unterminated quoted string in address header");
        }
        if ((openingAngle >= 0) != (closingAngle >= 0) || closingAngle < openingAngle) {
            throw new SipHeaderValueException("unbalanced angle brackets in address header");
        }
        int searchFrom = closingAngle >= 0 ? closingAngle + 1 : 0;
        return indexOfOutsideQuotes(value, ';', searchFrom);
    }

    private static List<SipParameter> parseParameters(String text, String headerName)
            throws SipHeaderValueException {
        List<SipParameter> parameters = new ArrayList<>();
        int position = 0;
        while (position < text.length()) {
            if (text.charAt(position) != ';') {
                throw new SipHeaderValueException(headerName + " parameter must start with ';'");
            }
            position++;
            while (position < text.length() && isLinearWhitespace(text.charAt(position))) {
                position++;
            }
            int nameStart = position;
            while (position < text.length()) {
                char character = text.charAt(position);
                if (character == '=' || character == ';' || isLinearWhitespace(character)) {
                    break;
                }
                position++;
            }
            if (position == nameStart) {
                throw new SipHeaderValueException(headerName + " contains an empty parameter name");
            }
            String name = text.substring(nameStart, position);
            while (position < text.length() && isLinearWhitespace(text.charAt(position))) {
                position++;
            }

            Optional<String> value = Optional.empty();
            if (position < text.length() && text.charAt(position) == '=') {
                position++;
                while (position < text.length() && isLinearWhitespace(text.charAt(position))) {
                    position++;
                }
                ParsedValue parsed = parseParameterValue(text, position, headerName);
                value = Optional.of(parsed.value());
                position = parsed.nextPosition();
            }
            try {
                parameters.add(new SipParameter(name, value));
            } catch (IllegalArgumentException exception) {
                throw new SipHeaderValueException("invalid " + headerName + " parameter name", exception);
            }
            while (position < text.length() && isLinearWhitespace(text.charAt(position))) {
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
            throw new SipHeaderValueException("unterminated quoted " + headerName + " parameter");
        }
        return new ParsedValue(decoded.toString(), index);
    }

    private static String requiredSingleValue(SipHeaders headers, String name) throws SipHeaderValueException {
        Objects.requireNonNull(headers, "headers");
        List<SipHeader> values = headers.all(name);
        if (values.isEmpty()) {
            throw new SipHeaderValueException("missing " + name + " header");
        }
        if (values.size() != 1) {
            throw new SipHeaderValueException("duplicate " + name + " headers");
        }
        return values.getFirst().value();
    }

    private static String firstListValue(String value) throws SipHeaderValueException {
        boolean quoted = false;
        boolean escaped = false;
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (escaped) {
                escaped = false;
            } else if (quoted && character == '\\') {
                escaped = true;
            } else if (character == '"') {
                quoted = !quoted;
            } else if (!quoted && character == ',') {
                return value.substring(0, index);
            }
        }
        if (quoted || escaped) {
            throw new SipHeaderValueException("unterminated quoted string in Via header");
        }
        return value;
    }

    private static int indexOfOutsideQuotes(String value, char searched, int start) {
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

    private static int firstWhitespace(String value) {
        for (int index = 0; index < value.length(); index++) {
            if (isLinearWhitespace(value.charAt(index))) {
                return index;
            }
        }
        return -1;
    }

    private static boolean isLinearWhitespace(char character) {
        return character == ' ' || character == '\t';
    }

    private static boolean isAsciiDigits(String value) {
        if (value.isEmpty()) {
            return false;
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character < '0' || character > '9') {
                return false;
            }
        }
        return true;
    }

    private record ParsedValue(String value, int nextPosition) {
    }
}
