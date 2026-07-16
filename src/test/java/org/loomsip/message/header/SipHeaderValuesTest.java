package org.loomsip.message.header;

import org.junit.jupiter.api.Test;
import org.loomsip.message.SipHeaders;
import org.loomsip.message.SipMethod;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SipHeaderValuesTest {

    @Test
    void parsesTopViaFromCompactCommaSeparatedField() throws Exception {
        SipHeaders headers = SipHeaders.builder()
                .add("v", "SIP/2.0/UDP Client.Example.com;branch=z9hG4bK-one;rport, "
                        + "SIP/2.0/TCP backup.example.com;branch=z9hG4bK-two")
                .build();

        ViaHeaderValue via = SipHeaderValues.topVia(headers);

        assertEquals(ViaTransport.UDP, via.transport());
        assertEquals(new SentBy("client.example.com", 0), via.sentBy());
        assertEquals(5060, via.sentBy().effectivePort(via.transport()));
        assertEquals("z9hG4bK-one", via.branch().orElseThrow());
        assertTrue(via.hasMagicCookie());
        assertTrue(via.hasRPort());
        assertTrue(via.rport().isEmpty());
    }

    @Test
    void parsesIpv6SentByAndValuedParameters() throws Exception {
        SipHeaders headers = SipHeaders.builder()
                .add("Via", "SIP/2.0/TLS [2001:db8::1]:5071;branch=Z9hG4bK-test"
                        + ";received=192.0.2.10;rport=5090;extension=\"a,b\"")
                .build();

        ViaHeaderValue via = SipHeaderValues.topVia(headers);

        assertEquals(ViaTransport.TLS, via.transport());
        assertEquals(new SentBy("2001:db8::1", 5071), via.sentBy());
        assertEquals("192.0.2.10", via.received().orElseThrow());
        assertEquals(5090, via.rport().orElseThrow());
        assertEquals("a,b", via.parameter("extension").orElseThrow());
        assertTrue(via.hasMagicCookie());
    }

    @Test
    void parsesCSeqTagsAndCallId() throws Exception {
        SipHeaders headers = SipHeaders.builder()
                .add("From", "\"Alice; A\" <sip:alice@example.com>;TAG=from-tag;custom=x")
                .add("To", "sip:bob@example.com;tag=to-tag")
                .add("Call-ID", "call-1@example.com")
                .add("CSeq", "2147483647 OPTIONS")
                .build();

        CSeqHeaderValue cseq = SipHeaderValues.cseq(headers);

        assertEquals(CSeqHeaderValue.MAX_SEQUENCE_NUMBER, cseq.sequenceNumber());
        assertEquals(SipMethod.OPTIONS, cseq.method());
        assertEquals("from-tag", SipHeaderValues.fromTag(headers).orElseThrow());
        assertEquals("to-tag", SipHeaderValues.toTag(headers).orElseThrow());
        assertEquals("call-1@example.com", SipHeaderValues.callId(headers));
    }

    @Test
    void reportsMissingDuplicateAndMalformedRoutingFields() {
        assertThrows(SipHeaderValueException.class,
                () -> SipHeaderValues.topVia(SipHeaders.empty()));

        SipHeaders duplicateCseq = SipHeaders.builder()
                .add("CSeq", "1 OPTIONS")
                .add("CSeq", "2 OPTIONS")
                .build();
        assertThrows(SipHeaderValueException.class, () -> SipHeaderValues.cseq(duplicateCseq));

        SipHeaders badVia = SipHeaders.builder()
                .add("Via", "SIP/2.0/UDP 2001:db8::1;branch=z9hG4bK-test")
                .build();
        assertThrows(SipHeaderValueException.class, () -> SipHeaderValues.topVia(badVia));

        SipHeaders badTag = SipHeaders.builder().add("From", "<sip:a@example.com>;tag").build();
        assertThrows(SipHeaderValueException.class, () -> SipHeaderValues.fromTag(badTag));
    }

    @Test
    void distinguishesAbsentRportFromValuedRport() throws Exception {
        ViaHeaderValue via = SipHeaderValues.topVia(
                SipHeaders.builder().add("Via", "SIP/2.0/UDP host;branch=legacy").build()
        );

        assertFalse(via.hasRPort());
        assertTrue(via.rport().isEmpty());
        assertFalse(via.hasMagicCookie());
    }
}
