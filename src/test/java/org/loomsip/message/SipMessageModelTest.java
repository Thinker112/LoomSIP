package org.loomsip.message;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SipMessageModelTest {

    @Test
    void bodyIsDefensivelyCopied() {
        byte[] source = {1, 2, 3};
        SipBody body = SipBody.of(source);

        source[0] = 9;
        byte[] returned = body.bytes();
        returned[1] = 9;

        assertArrayEquals(new byte[]{1, 2, 3}, body.bytes());
    }

    @Test
    void headersPreserveOrderAndMatchCompactNames() {
        SipHeaders headers = SipHeaders.builder()
                .add("v", "SIP/2.0/UDP first.example.com")
                .add("Via", "SIP/2.0/UDP second.example.com")
                .add("i", "call-1@example.com")
                .build();

        assertEquals(2, headers.all("Via").size());
        assertEquals("SIP/2.0/UDP first.example.com", headers.firstValue("via").orElseThrow());
        assertEquals("call-1@example.com", headers.firstValue("Call-ID").orElseThrow());
        assertThrows(UnsupportedOperationException.class,
                () -> headers.entries().add(new SipHeader("X-Test", "value")));
    }

    @Test
    void methodAndUriAllowProtocolExtensions() {
        SipMethod method = SipMethod.of("PUBLISH-STATE");
        SipUri uri = SipUri.parse("custom:resource;version=1");

        assertEquals("PUBLISH-STATE", method.value());
        assertEquals("custom", uri.scheme().orElseThrow());
        assertEquals("custom:resource;version=1", uri.toString());
        assertEquals(List.of(), SipHeaders.empty().entries());
    }

    @Test
    void headerRejectsLineInjection() {
        assertThrows(IllegalArgumentException.class, () -> new SipHeader("X-Test", "ok\r\nInjected: yes"));
    }
}
