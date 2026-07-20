package org.loomsip.message.header;

import org.junit.jupiter.api.Test;
import org.loomsip.message.SipHeaders;
import org.loomsip.message.SipMethod;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReliableProvisionalHeaderValuesTest {

    @Test
    void parsesRseqRackAndExtensionTags() throws Exception {
        SipHeaders headers = SipHeaders.builder()
                .add("RSeq", "7")
                .add("RAck", "7 42 INVITE")
                .add("Supported", "timer, 100rel")
                .add("Require", "100rel")
                .build();

        assertEquals(7, SipHeaderValues.rseq(headers).sequenceNumber());
        RAckHeaderValue rack = SipHeaderValues.rack(headers);
        assertEquals(7, rack.responseSequenceNumber());
        assertEquals(42, rack.inviteSequenceNumber());
        assertEquals(SipMethod.INVITE, rack.inviteMethod());
        assertTrue(SipExtensionSupport.contains(headers, "Supported", "100rel"));
        assertTrue(SipExtensionSupport.contains(headers, "Require", "100rel"));
        assertEquals("7 42 INVITE", rack.wireValue());
    }

    @Test
    void rejectsMalformedReliableProvisionalHeaders() {
        assertThrows(SipHeaderValueException.class,
                () -> SipHeaderValues.rseq(SipHeaders.builder().add("RSeq", "0").build()));
        assertThrows(SipHeaderValueException.class,
                () -> SipHeaderValues.rack(SipHeaders.builder().add("RAck", "1 2").build()));
        assertThrows(IllegalArgumentException.class,
                () -> SipExtensionSupport.tokens(SipHeaders.builder().add("Supported", "100rel, bad token").build(),
                        "Supported"));
    }
}
