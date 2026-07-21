package org.loomsip.message.header;

import org.junit.jupiter.api.Test;
import org.loomsip.message.SipHeaders;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReferHeaderValuesTest {

    @Test
    void parsesReferToAndReferSubDefault() throws Exception {
        SipHeaders headers = SipHeaders.builder()
                .add("Refer-To", "<sip:carol@example.com;transport=tcp>")
                .build();

        ReferToHeaderValue target = SipHeaderValues.referTo(headers);

        assertEquals("sip:carol@example.com;transport=tcp", target.address().uri().value());
        assertTrue(SipHeaderValues.referSub(headers).enabled());
        assertEquals("<sip:carol@example.com;transport=tcp>", target.wireValue());
    }

    @Test
    void parsesExplicitReferSubAndRejectsInvalidOrDuplicateFields() throws Exception {
        assertFalse(SipHeaderValues.referSub(SipHeaders.builder().add("Refer-Sub", "FALSE").build()).enabled());
        assertThrows(SipHeaderValueException.class, () -> SipHeaderValues.referSub(
                SipHeaders.builder().add("Refer-Sub", "maybe").build()
        ));
        assertThrows(SipHeaderValueException.class, () -> SipHeaderValues.referTo(
                SipHeaders.builder().add("Refer-To", "sip:a@example.com").add("Refer-To", "sip:b@example.com").build()
        ));
    }
}
