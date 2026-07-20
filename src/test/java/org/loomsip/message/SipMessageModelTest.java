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
    void standardExtensionMethodsReturnSharedValues() {
        assertEquals(SipMethod.PRACK, SipMethod.of("PRACK"));
        assertEquals(SipMethod.UPDATE, SipMethod.of("UPDATE"));
        assertEquals(SipMethod.INFO, SipMethod.of("INFO"));
        assertEquals(SipMethod.REFER, SipMethod.of("REFER"));
        assertEquals(SipMethod.SUBSCRIBE, SipMethod.of("SUBSCRIBE"));
        assertEquals(SipMethod.NOTIFY, SipMethod.of("NOTIFY"));
    }

    @Test
    void headersRemoveAndReplaceEquivalentCompactNamesWithoutReorderingOthers() {
        SipHeaders original = SipHeaders.builder()
                .add("X-First", "one")
                .add("v", "first-via")
                .add("Via", "second-via")
                .add("Call-ID", "call@example.com")
                .build();

        SipHeaders replaced = original.withReplaced("Via", "replacement-via");
        SipHeaders removed = original.without("Via");

        assertEquals(List.of(
                new SipHeader("X-First", "one"),
                new SipHeader("Via", "replacement-via"),
                new SipHeader("Call-ID", "call@example.com")
        ), replaced.entries());
        assertEquals(List.of(
                new SipHeader("X-First", "one"),
                new SipHeader("Call-ID", "call@example.com")
        ), removed.entries());
        assertEquals(2, original.all("Via").size());
    }

    @Test
    void requestBuilderRebuildsHeadersWithoutMutatingSource() {
        SipRequest source = new SipRequest(
                SipMethod.INVITE,
                SipUri.parse("sip:bob@example.com"),
                SipHeaders.builder()
                        .add("Via", "SIP/2.0/UDP client.example.com;branch=old")
                        .add("Call-ID", "call@example.com")
                        .add("CSeq", "1 INVITE")
                        .add("Authorization", "Digest old")
                        .build()
        );

        SipRequest retry = source.toBuilder()
                .replaceHeader("Via", "SIP/2.0/UDP client.example.com;branch=new")
                .replaceHeader("CSeq", "2 INVITE")
                .removeHeader("Authorization")
                .addHeader("Authorization", "Digest replacement")
                .build();

        assertEquals("1 INVITE", source.headers().firstValue("CSeq").orElseThrow());
        assertEquals("Digest old", source.headers().firstValue("Authorization").orElseThrow());
        assertEquals("2 INVITE", retry.headers().firstValue("CSeq").orElseThrow());
        assertEquals("Digest replacement", retry.headers().firstValue("Authorization").orElseThrow());
        assertEquals(1, retry.headers().all("Authorization").size());
    }

    @Test
    void headerRejectsLineInjection() {
        assertThrows(IllegalArgumentException.class, () -> new SipHeader("X-Test", "ok\r\nInjected: yes"));
    }
}
