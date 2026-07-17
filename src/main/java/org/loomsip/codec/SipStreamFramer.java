package org.loomsip.codec;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Incrementally extracts complete SIP wire frames from a TCP/TLS byte stream.
 *
 * <pre>{@code
 * byte[] chunk(s)
 *       |
 *       v
 * +------------------+
 * | SipStreamFramer  |
 * | headers / length |
 * | bounded buffer   |
 * +---------+--------+
 *           |
 *           v
 *   complete byte[] frames
 *           |
 *           v
 *   SipMessageParser
 * }</pre>
 *
 * <p>The framer does not parse SIP start-lines or construct SIP model objects.
 * It only determines message boundaries and leaves syntax validation to
 * {@link SipMessageParser}.</p>
 */
public final class SipStreamFramer {

    private static final byte CR = '\r';
    private static final byte LF = '\n';

    private final StreamBufferLimits limits;
    private byte[] cumulation = new byte[0];
    private SipStreamDecoderState state = SipStreamDecoderState.READING_HEADERS;

    /**
     * Creates a stream framer with explicit incremental limits.
     *
     * @param limits stream resource limits
     */
    public SipStreamFramer(StreamBufferLimits limits) {
        this.limits = Objects.requireNonNull(limits, "limits");
    }

    /**
     * Creates a framer using {@link StreamBufferLimits#DEFAULT}.
     */
    public SipStreamFramer() {
        this(StreamBufferLimits.DEFAULT);
    }

    /**
     * Returns the current incremental state.
     *
     * @return decoder state
     */
    public SipStreamDecoderState state() {
        return state;
    }

    /**
     * Returns the number of bytes retained while waiting for a complete frame.
     *
     * @return current cumulation size
     */
    public int bufferedBytes() {
        return cumulation.length;
    }

    /**
     * Appends one network chunk and emits every complete frame now available.
     *
     * @param chunk newly received bytes
     * @return fresh frame arrays owned by the caller
     * @throws SipParseException if framing or configured limits fail
     */
    public List<byte[]> feed(byte[] chunk) throws SipParseException {
        Objects.requireNonNull(chunk, "chunk");
        if (chunk.length == 0) {
            return List.of();
        }
        append(chunk);

        List<byte[]> frames = new ArrayList<>();
        while (true) {
            int delimiter = findHeaderDelimiter(cumulation);
            if (delimiter < 0) {
                validateIncompleteHeaders();
                state = SipStreamDecoderState.READING_HEADERS;
                break;
            }

            int startLineEnd = findCrlf(cumulation, 0, delimiter);
            if (startLineEnd < 0) {
                throw framing("header delimiter appears before a complete start-line", 0);
            }
            validateStartLine(startLineEnd);
            int headerBytes = delimiter - (startLineEnd + 2);
            if (headerBytes > limits.maxHeaderBytes()) {
                throw framing("stream headers exceed configured limit", startLineEnd + 2);
            }

            int bodyLength = contentLength(cumulation, startLineEnd + 2, delimiter);
            if (bodyLength > limits.maxBodyBytes()) {
                throw framing("stream body exceeds configured limit", delimiter + 4);
            }
            long frameLength = (long) delimiter + 4 + bodyLength;
            if (frameLength > limits.maxMessageBytes()) {
                throw framing("stream message exceeds configured limit", 0);
            }
            if (frameLength > cumulation.length) {
                state = SipStreamDecoderState.READING_BODY;
                break;
            }

            int length = Math.toIntExact(frameLength);
            frames.add(Arrays.copyOf(cumulation, length));
            cumulation = Arrays.copyOfRange(cumulation, length, cumulation.length);
            state = SipStreamDecoderState.READING_HEADERS;
            if (cumulation.length == 0) {
                break;
            }
        }
        return List.copyOf(frames);
    }

    /**
     * Signals end-of-stream and rejects an incomplete message.
     *
     * @throws SipParseException if bytes remain buffered
     */
    public void endOfInput() throws SipParseException {
        if (cumulation.length != 0) {
            throw framing("stream ended with an incomplete SIP message", cumulation.length);
        }
    }

    /** Clears buffered bytes after a connection-level framing failure. */
    public void reset() {
        cumulation = new byte[0];
        state = SipStreamDecoderState.READING_HEADERS;
    }

    private void append(byte[] chunk) throws SipParseException {
        long required = (long) cumulation.length + chunk.length;
        if (required > limits.maxCumulationBytes()) {
            throw framing("stream cumulation exceeds configured limit", cumulation.length);
        }
        byte[] combined = Arrays.copyOf(cumulation, Math.toIntExact(required));
        System.arraycopy(chunk, 0, combined, cumulation.length, chunk.length);
        cumulation = combined;
    }

    private void validateIncompleteHeaders() throws SipParseException {
        int startLineEnd = findCrlf(cumulation, 0, cumulation.length);
        if (startLineEnd >= 0) {
            validateStartLine(startLineEnd);
            int headerBytes = cumulation.length - (startLineEnd + 2);
            if (headerBytes > limits.maxHeaderBytes() + 3) {
                throw framing("stream headers exceed configured limit", startLineEnd + 2);
            }
            if (cumulation.length > limits.maxMessageBytes()) {
                throw framing("stream message exceeds configured limit", 0);
            }
            return;
        }
        if (cumulation.length > limits.maxStartLineBytes() + 2) {
            throw framing("stream start-line exceeds configured limit", 0);
        }
        if (cumulation.length > limits.maxMessageBytes()) {
            throw framing("stream message exceeds configured limit", 0);
        }
    }

    private void validateStartLine(int lineEnd) throws SipParseException {
        if (lineEnd > limits.maxStartLineBytes()) {
            throw framing("stream start-line exceeds configured limit", 0);
        }
    }

    private static int contentLength(byte[] data, int start, int end) throws SipParseException {
        Integer selected = null;
        String currentName = null;
        StringBuilder currentValue = null;
        int position = start;
        while (position < end) {
            int lineEnd = findCrlf(data, position, end);
            if (lineEnd < 0) {
                lineEnd = end;
            }
            if (lineEnd == position) {
                throw framing("stream Header section contains an empty line", position);
            }
            String line = ascii(data, position, lineEnd - position);
            if (line.charAt(0) == ' ' || line.charAt(0) == '\t') {
                if (currentName == null) {
                    throw framing("stream Header continuation has no preceding field", position);
                }
                currentValue.append(' ').append(line.strip());
            } else {
                if (currentName != null) {
                    selected = selectContentLength(currentName, currentValue.toString(), selected, position);
                }
                int colon = line.indexOf(':');
                if (colon <= 0) {
                    throw framing("stream Header has no valid name separator", position);
                }
                currentName = line.substring(0, colon).strip().toLowerCase(Locale.ROOT);
                currentValue = new StringBuilder(line.substring(colon + 1).strip());
            }
            position = lineEnd == end ? end : lineEnd + 2;
        }
        if (currentName != null) {
            selected = selectContentLength(currentName, currentValue.toString(), selected, start);
        }
        return selected == null ? 0 : selected;
    }

    private static Integer selectContentLength(
            String name,
            String value,
            Integer selected,
            int offset
    ) throws SipParseException {
        if (!name.equals("content-length") && !name.equals("l")) {
            return selected;
        }
        if (value.isEmpty() || value.chars().anyMatch(character -> character < '0' || character > '9')) {
            throw framing("invalid stream Content-Length", offset);
        }
        final int parsed;
        try {
            parsed = Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            throw new SipParseException("stream Content-Length is too large", offset, exception);
        }
        if (selected != null && selected != parsed) {
            throw framing("conflicting stream Content-Length headers", offset);
        }
        return parsed;
    }

    private static String ascii(byte[] data, int offset, int length) {
        return new String(data, offset, length, StandardCharsets.US_ASCII);
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

    private static SipParseException framing(String message, int offset) {
        return new SipParseException(message, offset);
    }
}
