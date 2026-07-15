package org.loomsip.codec;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.loomsip.message.SipMessage;
import org.loomsip.message.SipRequest;
import org.loomsip.message.SipResponse;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SipMessageParserTest {

    private final SipMessageParser parser = new SipMessageParser();

    @ParameterizedTest
    @MethodSource("commonMessages")
    void parsesCommonRequestAndResponseMessages(String startLine, String expected) throws Exception {
        SipMessage message = parser.parse(message(startLine, "Content-Length: 0", new byte[0]));

        if (message instanceof SipRequest request) {
            assertEquals(expected, request.method().value());
            assertEquals("sip:service@example.com", request.requestUri().value());
        } else {
            assertEquals(Integer.parseInt(expected), ((SipResponse) message).statusCode());
        }
    }

    static Stream<Arguments> commonMessages() {
        return Stream.of(
                Arguments.of("OPTIONS sip:service@example.com SIP/2.0", "OPTIONS"),
                Arguments.of("REGISTER sip:service@example.com SIP/2.0", "REGISTER"),
                Arguments.of("INVITE sip:service@example.com SIP/2.0", "INVITE"),
                Arguments.of("SIP/2.0 100 Trying", "100"),
                Arguments.of("SIP/2.0 180 Ringing", "180"),
                Arguments.of("SIP/2.0 200 OK", "200"),
                Arguments.of("SIP/2.0 204 ", "204"),
                Arguments.of("SIP/2.0 404 Not Found", "404")
        );
    }

    @Test
    void preservesRepeatedCompactAndUnknownHeaders() throws Exception {
        String headers = "v: SIP/2.0/UDP first.example.com;branch=z9hG4bK-1\r\n"
                + "Via: SIP/2.0/TCP second.example.com;branch=z9hG4bK-2\r\n"
                + "f: <sip:alice@example.com>;tag=from-1\r\n"
                + "t: <sip:bob@example.com>\r\n"
                + "i: call-1@example.com\r\n"
                + "X-Trace-Id: trace-123\r\n"
                + "l: 0";

        SipRequest request = assertInstanceOf(SipRequest.class,
                parser.parse(message("INVITE sip:bob@example.com SIP/2.0", headers, new byte[0])));

        assertEquals(2, request.headers().all("Via").size());
        assertEquals("SIP/2.0/UDP first.example.com;branch=z9hG4bK-1",
                request.headers().firstValue("Via").orElseThrow());
        assertEquals("call-1@example.com", request.headers().firstValue("Call-ID").orElseThrow());
        assertEquals("trace-123", request.headers().firstValue("x-trace-id").orElseThrow());
    }

    @Test
    void unfoldsContinuationLines() throws Exception {
        String headers = "Subject: a project\r\n"
                + "\tthat spans two lines\r\n"
                + "Content-Length: 0";

        SipMessage message = parser.parse(message("OPTIONS sip:service@example.com SIP/2.0", headers, new byte[0]));

        assertEquals("a project that spans two lines", message.headers().firstValue("Subject").orElseThrow());
    }

    @Test
    void acceptsUnknownMethodAndBodyWithoutContentLength() throws Exception {
        byte[] body = "payload".getBytes(StandardCharsets.UTF_8);

        SipRequest request = assertInstanceOf(SipRequest.class,
                parser.parse(message("PUBLISH-STATE sip:service@example.com SIP/2.0", "X-Test: yes", body)));

        assertEquals("PUBLISH-STATE", request.method().value());
        assertArrayEquals(body, request.body().bytes());
    }

    @Test
    void contentLengthUsesBytesRatherThanCharacters() throws Exception {
        byte[] body = "你好，SIP".getBytes(StandardCharsets.UTF_8);
        String headers = "Content-Type: text/plain; charset=UTF-8\r\nContent-Length: " + body.length;

        SipMessage parsed = parser.parse(message("OPTIONS sip:service@example.com SIP/2.0", headers, body));

        assertEquals(body.length, parsed.body().length());
        assertArrayEquals(body, parsed.body().bytes());
    }

    @Test
    void preservesBinaryBody() throws Exception {
        byte[] body = {0, 1, 2, 13, 10, (byte) 0xff};

        SipMessage parsed = parser.parse(message(
                "OPTIONS sip:service@example.com SIP/2.0",
                "Content-Length: " + body.length,
                body
        ));

        assertArrayEquals(body, parsed.body().bytes());
    }

    @Test
    void rejectsContentLengthMismatchAndConflicts() {
        SipParseException mismatch = assertThrows(SipParseException.class, () -> parser.parse(message(
                "OPTIONS sip:service@example.com SIP/2.0",
                "Content-Length: 5",
                new byte[]{1, 2}
        )));
        SipParseException conflict = assertThrows(SipParseException.class, () -> parser.parse(message(
                "OPTIONS sip:service@example.com SIP/2.0",
                "Content-Length: 0\r\nl: 1",
                new byte[0]
        )));

        assertTrue(mismatch.getMessage().contains("actual body length"));
        assertTrue(conflict.getMessage().contains("conflicting Content-Length"));
        assertTrue(mismatch.byteOffset() > 0);
    }

    @Test
    void rejectsNonAsciiContentLengthDigits() {
        assertThrows(SipParseException.class, () -> parser.parse(message(
                "OPTIONS sip:service@example.com SIP/2.0",
                "Content-Length: ０",
                new byte[0]
        )));
    }

    @Test
    void rejectsMalformedStartLineAndHeader() {
        SipParseException startLine = assertThrows(SipParseException.class, () -> parser.parse(message(
                "BROKEN",
                "Content-Length: 0",
                new byte[0]
        )));
        SipParseException header = assertThrows(SipParseException.class, () -> parser.parse(message(
                "OPTIONS sip:service@example.com SIP/2.0",
                "Not-A-Header",
                new byte[0]
        )));

        assertEquals(0, startLine.byteOffset());
        assertTrue(header.byteOffset() > 0);
    }

    @Test
    void enforcesConfiguredLimits() {
        SipMessageParser limitedParser = new SipMessageParser(new SipParserLimits(16, 16, 2));

        assertThrows(SipParseException.class, () -> limitedParser.parse(message(
                "OPTIONS sip:service@example.com SIP/2.0",
                "Content-Length: 0",
                new byte[0]
        )));
    }

    private static byte[] message(String startLine, String headers, byte[] body) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.writeBytes((startLine + "\r\n").getBytes(StandardCharsets.UTF_8));
        if (!headers.isEmpty()) {
            output.writeBytes(headers.getBytes(StandardCharsets.UTF_8));
        }
        output.writeBytes("\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
        output.writeBytes(body);
        return output.toByteArray();
    }
}
