package org.loomsip.codec;

import org.junit.jupiter.api.Test;
import org.loomsip.message.SipMessage;
import org.loomsip.message.SipRequest;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SipStreamFramerTest {

    private final SipMessageParser parser = new SipMessageParser();

    @Test
    void framesOneByteAtATime() throws Exception {
        byte[] message = request("one-byte", "");
        SipStreamFramer framer = new SipStreamFramer();
        List<byte[]> frames = new ArrayList<>();

        for (byte value : message) {
            frames.addAll(framer.feed(new byte[]{value}));
        }

        assertEquals(1, frames.size());
        assertArrayEquals(message, frames.getFirst());
        assertEquals(SipStreamDecoderState.READING_HEADERS, framer.state());
        assertEquals(0, framer.bufferedBytes());
        assertEquals("one-byte@example.com", callId(parser.parse(frames.getFirst())));
    }

    @Test
    void waitsForSplitBodyAndTracksState() throws Exception {
        byte[] message = request("split-body", "hello");
        int bodyStart = indexOf(message, "\r\n\r\n".getBytes(StandardCharsets.US_ASCII)) + 4;
        SipStreamFramer framer = new SipStreamFramer();

        assertTrue(framer.feed(Arrays.copyOf(message, bodyStart + 2)).isEmpty());
        assertEquals(SipStreamDecoderState.READING_BODY, framer.state());

        List<byte[]> frames = framer.feed(Arrays.copyOfRange(message, bodyStart + 2, message.length));
        assertEquals(1, frames.size());
        assertArrayEquals(message, frames.getFirst());
        assertEquals("hello", new String(
                parser.parse(frames.getFirst()).body().bytes(),
                StandardCharsets.UTF_8
        ));
    }

    @Test
    void emitsMultipleStickyMessagesIncludingBody() throws Exception {
        byte[] first = request("first", "abc");
        byte[] second = request("second", "");
        byte[] third = request("third", "xyz");
        byte[] combined = concat(first, second, third);

        List<byte[]> frames = new SipStreamFramer().feed(combined);

        assertEquals(3, frames.size());
        assertEquals(List.of(
                "first@example.com",
                "second@example.com",
                "third@example.com"
        ), frames.stream().map(frame -> {
            try {
                return callId(parser.parse(frame));
            } catch (SipParseException exception) {
                throw new AssertionError(exception);
            }
        }).toList());
    }

    @Test
    void supportsHeaderDelimiterSplitAcrossChunks() throws Exception {
        byte[] message = request("delimiter", "");
        int delimiter = indexOf(message, "\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
        SipStreamFramer framer = new SipStreamFramer();

        assertTrue(framer.feed(Arrays.copyOf(message, delimiter + 1)).isEmpty());
        assertTrue(framer.feed(Arrays.copyOfRange(message, delimiter + 1, delimiter + 3)).isEmpty());
        List<byte[]> frames = framer.feed(Arrays.copyOfRange(message, delimiter + 3, message.length));

        assertEquals(1, frames.size());
        assertArrayEquals(message, frames.getFirst());
    }

    @Test
    void missingContentLengthMeansZeroBodyAndPreservesNextMessage() throws Exception {
        byte[] first = requestWithoutLength("no-length");
        byte[] second = request("next", "");

        List<byte[]> frames = new SipStreamFramer().feed(concat(first, second));

        assertEquals(2, frames.size());
        assertArrayEquals(first, frames.getFirst());
        assertArrayEquals(second, frames.get(1));
    }

    @Test
    void acceptsConsistentCompactAndLongContentLength() throws Exception {
        byte[] message = wire(
                "OPTIONS sip:service@example.com SIP/2.0\r\n"
                        + baseHeaders("duplicate-length")
                        + "Content-Length: 0\r\n"
                        + "l: 0\r\n\r\n"
        );

        List<byte[]> frames = new SipStreamFramer().feed(message);

        assertEquals(1, frames.size());
        assertEquals("duplicate-length@example.com", callId(parser.parse(frames.getFirst())));
    }

    @Test
    void rejectsConflictingOrInvalidContentLength() {
        byte[] conflicting = wire(
                "OPTIONS sip:service@example.com SIP/2.0\r\n"
                        + baseHeaders("conflicting")
                        + "Content-Length: 0\r\n"
                        + "l: 1\r\n\r\n"
        );
        byte[] invalid = wire(
                "OPTIONS sip:service@example.com SIP/2.0\r\n"
                        + baseHeaders("invalid")
                        + "Content-Length: nope\r\n\r\n"
        );

        assertThrows(SipParseException.class, () -> new SipStreamFramer().feed(conflicting));
        assertThrows(SipParseException.class, () -> new SipStreamFramer().feed(invalid));
    }

    @Test
    void enforcesStartLineHeaderBodyMessageAndCumulationLimits() {
        assertThrows(SipParseException.class, () -> new SipStreamFramer(limits(8, 128, 16, 256, 256))
                .feed(wire("OPTIONS sip:service@example.com SIP/2.0")));

        assertThrows(SipParseException.class, () -> new SipStreamFramer(limits(128, 8, 16, 256, 256))
                .feed(request("header-limit", "")));

        assertThrows(SipParseException.class, () -> new SipStreamFramer(limits(128, 512, 2, 1_024, 1_024))
                .feed(request("body-limit", "abc")));

        assertThrows(SipParseException.class, () -> new SipStreamFramer(limits(128, 512, 16, 64, 1_024))
                .feed(request("message-limit", "")));

        assertThrows(SipParseException.class, () -> new SipStreamFramer(limits(128, 512, 16, 64, 1_024))
                .feed(wire("OPTIONS sip:a@b SIP/2.0\r\nX-Test: " + "a".repeat(64))));

        assertThrows(SipParseException.class, () -> new SipStreamFramer(limits(128, 128, 16, 20, 20))
                .feed(wire("123456789012345678901")));
    }

    @Test
    void endOfInputRejectsIncompleteHeadersOrBodyAndResetRecovers() throws Exception {
        SipStreamFramer framer = new SipStreamFramer();
        framer.feed(wire("OPTIONS sip:service@example.com SIP/2.0\r\n"));
        assertThrows(SipParseException.class, framer::endOfInput);

        framer.reset();
        byte[] body = request("partial-body", "hello");
        framer.feed(Arrays.copyOf(body, body.length - 1));
        assertEquals(SipStreamDecoderState.READING_BODY, framer.state());
        assertThrows(SipParseException.class, framer::endOfInput);

        framer.reset();
        assertEquals(1, framer.feed(request("recovered", "")).size());
        framer.endOfInput();
    }

    private static StreamBufferLimits limits(
            int startLine,
            int headers,
            int body,
            int message,
            int cumulation
    ) {
        return new StreamBufferLimits(startLine, headers, body, message, cumulation);
    }

    private static byte[] request(String id, String body) {
        byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);
        return wire(
                "OPTIONS sip:service@example.com SIP/2.0\r\n"
                        + baseHeaders(id)
                        + "Content-Type: text/plain\r\n"
                        + "Content-Length: " + bodyBytes.length + "\r\n\r\n"
                        + body
        );
    }

    private static byte[] requestWithoutLength(String id) {
        return wire(
                "OPTIONS sip:service@example.com SIP/2.0\r\n"
                        + baseHeaders(id)
                        + "\r\n"
        );
    }

    private static String baseHeaders(String id) {
        return "Via: SIP/2.0/TCP client.example.com;branch=z9hG4bK-" + id + "\r\n"
                + "From: <sip:alice@example.com>;tag=alice\r\n"
                + "To: <sip:service@example.com>\r\n"
                + "Call-ID: " + id + "@example.com\r\n"
                + "CSeq: 1 OPTIONS\r\n"
                + "Max-Forwards: 70\r\n";
    }

    private static String callId(SipMessage message) {
        return ((SipRequest) message).headers().firstValue("Call-ID").orElseThrow();
    }

    private static byte[] wire(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] concat(byte[]... values) {
        int length = Arrays.stream(values).mapToInt(value -> value.length).sum();
        byte[] combined = new byte[length];
        int offset = 0;
        for (byte[] value : values) {
            System.arraycopy(value, 0, combined, offset, value.length);
            offset += value.length;
        }
        return combined;
    }

    private static int indexOf(byte[] source, byte[] target) {
        for (int index = 0; index <= source.length - target.length; index++) {
            boolean matched = true;
            for (int targetIndex = 0; targetIndex < target.length; targetIndex++) {
                if (source[index + targetIndex] != target[targetIndex]) {
                    matched = false;
                    break;
                }
            }
            if (matched) {
                return index;
            }
        }
        throw new IllegalArgumentException("target not found");
    }
}
