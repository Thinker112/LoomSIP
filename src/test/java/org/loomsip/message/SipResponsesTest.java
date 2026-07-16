package org.loomsip.message;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SipResponsesTest {

    @Test
    void createsResponseWithCorrelationHeadersAndLocalTag() {
        SipRequest request = request("<sip:bob@example.com>");

        SipResponse response = SipResponses.createResponse(request, 200, "OK", "server-tag");

        assertEquals(200, response.statusCode());
        assertEquals(2, response.headers().all("Via").size());
        assertEquals("<sip:alice@example.com>;tag=client-tag",
                response.headers().firstValue("From").orElseThrow());
        assertEquals("<sip:bob@example.com>;tag=server-tag",
                response.headers().firstValue("To").orElseThrow());
        assertEquals("call-1@example.com", response.headers().firstValue("Call-ID").orElseThrow());
        assertEquals("1 OPTIONS", response.headers().firstValue("CSeq").orElseThrow());
    }

    @Test
    void preservesAnExistingToTag() {
        SipRequest request = request("<sip:bob@example.com>;tag=existing-tag");

        SipResponse response = SipResponses.createResponse(request, 200, "OK", "replacement-tag");

        assertEquals("<sip:bob@example.com>;tag=existing-tag",
                response.headers().firstValue("To").orElseThrow());
    }

    @Test
    void rejectsRequestMissingRequiredCorrelationHeader() {
        SipRequest request = new SipRequest(
                SipMethod.OPTIONS,
                SipUri.parse("sip:bob@example.com"),
                SipHeaders.builder().add("Via", "SIP/2.0/UDP client.example.com").build()
        );

        assertThrows(IllegalArgumentException.class,
                () -> SipResponses.createResponse(request, 200, "OK", "server-tag"));
    }

    private static SipRequest request(String to) {
        return new SipRequest(
                SipMethod.OPTIONS,
                SipUri.parse("sip:bob@example.com"),
                SipHeaders.builder()
                        .add("Via", "SIP/2.0/UDP first.example.com;branch=z9hG4bK-1")
                        .add("Via", "SIP/2.0/TCP second.example.com;branch=z9hG4bK-2")
                        .add("From", "<sip:alice@example.com>;tag=client-tag")
                        .add("To", to)
                        .add("Call-ID", "call-1@example.com")
                        .add("CSeq", "1 OPTIONS")
                        .build()
        );
    }
}
