package org.loomsip.codec;

import org.loomsip.message.SipBody;
import org.loomsip.message.SipHeader;
import org.loomsip.message.SipHeaders;
import org.loomsip.message.SipMessage;
import org.loomsip.message.SipMethod;
import org.loomsip.message.SipRequest;
import org.loomsip.message.SipResponse;
import org.loomsip.message.SipUri;
import org.loomsip.message.SipVersion;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Parses one complete, already framed SIP message with configurable size limits.
 *
 * <p>The parser does not perform TCP stream reassembly. When a
 * {@code Content-Length} field is present, the supplied byte array must contain
 * exactly that many body bytes. Without the field, all bytes after the header
 * delimiter are treated as the body.</p>
 *
 * <pre>{@code
 * Complete wire bytes
 *        |
 *        v
 * +-------------------+
 * | SipMessageParser  |
 * | - framing checks  |
 * | - size limits     |
 * | - header parsing  |
 * | - body validation |
 * +---------+---------+
 *           |
 *           v
 * SipRequest or SipResponse
 * }</pre>
 */
public final class SipMessageParser {

    private static final byte CR = '\r';
    private static final byte LF = '\n';

    private final SipParserLimits limits;

    /**
     * Creates a parser using {@link SipParserLimits#DEFAULT}.
     */
    public SipMessageParser() {
        this(SipParserLimits.DEFAULT);
    }

    /**
     * Creates a parser with explicit resource limits.
     *
     * @param limits start-line, header, and body limits
     * @throws NullPointerException if {@code limits} is {@code null}
     */
    public SipMessageParser(SipParserLimits limits) {
        this.limits = Objects.requireNonNull(limits, "limits");
    }

    /**
     * Parses one complete SIP message.
     *
     * @param data complete wire-format message bytes
     * @return immutable request or response
     * @throws NullPointerException if {@code data} is {@code null}
     * @throws SipParseException if framing, UTF-8 text, syntax, limits, or body
     *                           length validation fails
     */
    public SipMessage parse(byte[] data) throws SipParseException {
        Objects.requireNonNull(data, "data");

        int headersEnd = findHeaderDelimiter(data);
        if (headersEnd < 0) {
            throw new SipParseException("missing CRLF CRLF header delimiter", data.length);
        }

        int startLineEnd = findCrlf(data, 0, headersEnd + 2);
        if (startLineEnd < 0) {
            throw new SipParseException("missing CRLF after start-line", 0);
        }
        if (startLineEnd == 0) {
            throw new SipParseException("empty start-line", 0);
        }
        if (startLineEnd > limits.maxStartLineBytes()) {
            throw new SipParseException("start-line exceeds configured limit", 0);
        }

        int headerStart = startLineEnd + 2;
        int headerBytes = Math.max(0, headersEnd - headerStart);
        if (headerBytes > limits.maxHeaderBytes()) {
            throw new SipParseException("headers exceed configured limit", headerStart);
        }

        String startLine = decodeUtf8(data, 0, startLineEnd, "invalid UTF-8 in start-line");
        SipHeaders headers = parseHeaders(data, headerStart, headersEnd);

        int bodyStart = headersEnd + 4;
        int actualBodyLength = data.length - bodyStart;
        int declaredBodyLength = contentLength(headers, headerStart);
        int expectedBodyLength = declaredBodyLength >= 0 ? declaredBodyLength : actualBodyLength;

        if (expectedBodyLength > limits.maxBodyBytes() || actualBodyLength > limits.maxBodyBytes()) {
            throw new SipParseException("body exceeds configured limit", bodyStart);
        }
        if (declaredBodyLength >= 0 && declaredBodyLength != actualBodyLength) {
            throw new SipParseException(
                    "Content-Length is " + declaredBodyLength + " but actual body length is " + actualBodyLength,
                    bodyStart
            );
        }

        SipBody body = SipBody.of(Arrays.copyOfRange(data, bodyStart, data.length));
        return parseStartLine(startLine, headers, body);
    }

    private SipHeaders parseHeaders(byte[] data, int headerStart, int headersEnd) throws SipParseException {
        if (headerStart >= headersEnd) {
            return SipHeaders.empty();
        }

        SipHeaders.Builder headers = SipHeaders.builder();
        String currentName = null;
        StringBuilder currentValue = null;
        int currentOffset = headerStart;
        int position = headerStart;

        while (position < headersEnd) {
            int lineEnd = findCrlf(data, position, headersEnd);
            if (lineEnd < 0) {
                lineEnd = headersEnd;
            }
            if (lineEnd == position) {
                throw new SipParseException("unexpected empty header line", position);
            }

            String line = decodeUtf8(data, position, lineEnd - position, "invalid UTF-8 in header");
            if (line.charAt(0) == ' ' || line.charAt(0) == '\t') {
                if (currentName == null) {
                    throw new SipParseException("header continuation has no preceding header", position);
                }
                currentValue.append(' ').append(line.strip());
            } else {
                if (currentName != null) {
                    addHeader(headers, currentName, currentValue.toString(), currentOffset);
                }
                int colon = line.indexOf(':');
                if (colon <= 0) {
                    throw new SipParseException("header line has no valid name separator", position);
                }
                currentName = line.substring(0, colon);
                currentValue = new StringBuilder(line.substring(colon + 1).strip());
                currentOffset = position;
            }

            position = lineEnd == headersEnd ? headersEnd : lineEnd + 2;
        }

        if (currentName != null) {
            addHeader(headers, currentName, currentValue.toString(), currentOffset);
        }
        return headers.build();
    }

    private static void addHeader(
            SipHeaders.Builder headers,
            String name,
            String value,
            int offset
    ) throws SipParseException {
        try {
            headers.add(new SipHeader(name, value));
        } catch (IllegalArgumentException exception) {
            throw new SipParseException("invalid header: " + exception.getMessage(), offset, exception);
        }
    }

    private static int contentLength(SipHeaders headers, int offset) throws SipParseException {
        List<SipHeader> values = headers.all("Content-Length");
        if (values.isEmpty()) {
            return -1;
        }

        Integer contentLength = null;
        for (SipHeader header : values) {
            String value = header.value().strip();
            if (!isAsciiDigits(value)) {
                throw new SipParseException("invalid Content-Length: " + header.value(), offset);
            }
            final int parsed;
            try {
                parsed = Integer.parseInt(value);
            } catch (NumberFormatException exception) {
                throw new SipParseException("Content-Length is too large", offset, exception);
            }
            if (contentLength != null && contentLength != parsed) {
                throw new SipParseException("conflicting Content-Length headers", offset);
            }
            contentLength = parsed;
        }
        return contentLength;
    }

    private static SipMessage parseStartLine(String startLine, SipHeaders headers, SipBody body)
            throws SipParseException {
        try {
            if (startLine.startsWith("SIP/")) {
                return parseResponseLine(startLine, headers, body);
            }
            return parseRequestLine(startLine, headers, body);
        } catch (IllegalArgumentException exception) {
            throw new SipParseException("invalid start-line: " + exception.getMessage(), 0, exception);
        }
    }

    private static SipRequest parseRequestLine(String line, SipHeaders headers, SipBody body) {
        int firstSpace = line.indexOf(' ');
        int lastSpace = line.lastIndexOf(' ');
        if (firstSpace <= 0 || lastSpace <= firstSpace + 1 || lastSpace == line.length() - 1) {
            throw new IllegalArgumentException("expected METHOD request-uri SIP/version");
        }
        if (line.indexOf('\t') >= 0 || line.indexOf(' ', firstSpace + 1) != lastSpace) {
            throw new IllegalArgumentException("start-line must use exactly two SP separators");
        }

        SipMethod method = SipMethod.of(line.substring(0, firstSpace));
        SipUri requestUri = SipUri.parse(line.substring(firstSpace + 1, lastSpace));
        SipVersion version = SipVersion.of(line.substring(lastSpace + 1));
        return new SipRequest(method, requestUri, version, headers, body);
    }

    private static SipResponse parseResponseLine(String line, SipHeaders headers, SipBody body) {
        int firstSpace = line.indexOf(' ');
        if (firstSpace <= 0 || firstSpace == line.length() - 1 || line.indexOf('\t') >= 0) {
            throw new IllegalArgumentException("expected SIP/version status-code reason-phrase");
        }

        int secondSpace = line.indexOf(' ', firstSpace + 1);
        if (secondSpace < 0) {
            throw new IllegalArgumentException("reason phrase separator is missing");
        }
        String statusText = line.substring(firstSpace + 1, secondSpace);
        if (statusText.length() != 3 || !isAsciiDigits(statusText)) {
            throw new IllegalArgumentException("status code must contain exactly three digits");
        }

        SipVersion version = SipVersion.of(line.substring(0, firstSpace));
        int statusCode = Integer.parseInt(statusText);
        String reasonPhrase = line.substring(secondSpace + 1);
        return new SipResponse(version, statusCode, reasonPhrase, headers, body);
    }

    private static boolean isAsciiDigits(String value) {
        if (value.isEmpty()) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            if (character < '0' || character > '9') {
                return false;
            }
        }
        return true;
    }

    private static String decodeUtf8(byte[] data, int offset, int length, String errorMessage)
            throws SipParseException {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(data, offset, length))
                    .toString();
        } catch (CharacterCodingException exception) {
            throw new SipParseException(errorMessage, offset, exception);
        }
    }

    private static int findHeaderDelimiter(byte[] data) {
        for (int i = 0; i <= data.length - 4; i++) {
            if (data[i] == CR && data[i + 1] == LF && data[i + 2] == CR && data[i + 3] == LF) {
                return i;
            }
        }
        return -1;
    }

    private static int findCrlf(byte[] data, int start, int limit) {
        int lastStart = Math.min(limit, data.length) - 2;
        for (int i = start; i <= lastStart; i++) {
            if (data[i] == CR && data[i + 1] == LF) {
                return i;
            }
        }
        return -1;
    }
}
