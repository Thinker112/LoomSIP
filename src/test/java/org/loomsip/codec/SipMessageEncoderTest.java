package org.loomsip.codec;

import org.junit.jupiter.api.Test;
import org.loomsip.message.SipBody;
import org.loomsip.message.SipHeaders;
import org.loomsip.message.SipMessage;
import org.loomsip.message.SipMethod;
import org.loomsip.message.SipRequest;
import org.loomsip.message.SipUri;
import org.loomsip.message.SipVersion;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SipMessageEncoderTest {

    private final SipMessageEncoder encoder = new SipMessageEncoder();
    private final SipMessageParser parser = new SipMessageParser();

    @Test
    void addsContentLengthAndSupportsSemanticRoundTrip() throws Exception {
        SipRequest request = new SipRequest(
                SipMethod.OPTIONS,
                SipUri.parse("sip:bob@example.com"),
                SipVersion.SIP_2_0,
                SipHeaders.builder()
                        .add("Via", "SIP/2.0/UDP client.example.com;branch=z9hG4bK-1")
                        .add("Call-ID", "call-1@example.com")
                        .build(),
                SipBody.empty()
        );

        byte[] encoded = encoder.encode(request);
        SipMessage reparsed = parser.parse(encoded);

        assertEquals(request.method(), ((SipRequest) reparsed).method());
        assertEquals(request.requestUri(), ((SipRequest) reparsed).requestUri());
        assertEquals(request.body(), reparsed.body());
        assertEquals("0", reparsed.headers().firstValue("Content-Length").orElseThrow());
        assertTrue(new String(encoded, StandardCharsets.UTF_8).endsWith("\r\n\r\n"));
    }

    @Test
    void replacesDuplicateContentLengthWithActualBinaryBodyLength() throws Exception {
        byte[] body = {0, 1, 2, (byte) 0xff};
        SipRequest request = new SipRequest(
                SipMethod.OPTIONS,
                SipUri.parse("sip:bob@example.com"),
                SipVersion.SIP_2_0,
                SipHeaders.builder()
                        .add("Content-Length", "999")
                        .add("l", "998")
                        .add("X-Test", "preserved")
                        .build(),
                SipBody.of(body)
        );

        byte[] encoded = encoder.encode(request);
        SipMessage reparsed = parser.parse(encoded);

        assertEquals(1, reparsed.headers().all("Content-Length").size());
        assertEquals(Integer.toString(body.length),
                reparsed.headers().firstValue("Content-Length").orElseThrow());
        assertEquals("preserved", reparsed.headers().firstValue("X-Test").orElseThrow());
        assertArrayEquals(body, reparsed.body().bytes());
    }

    @Test
    void emitsCrlfWithoutBareLineFeeds() {
        SipRequest request = new SipRequest(
                SipMethod.OPTIONS,
                SipUri.parse("sip:bob@example.com"),
                SipHeaders.empty()
        );

        String encoded = new String(encoder.encode(request), StandardCharsets.UTF_8);

        assertTrue(encoded.contains("\r\nContent-Length: 0\r\n\r\n"));
        assertFalse(encoded.replace("\r\n", "").contains("\n"));
    }
}
