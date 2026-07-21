package org.loomsip.refer;

import org.junit.jupiter.api.Test;
import org.loomsip.message.SipBody;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SipfragStatusTest {

    @Test
    void roundTripsMinimalSipfragStatus() {
        SipfragStatus status = new SipfragStatus(180, "Ringing");

        SipfragStatus parsed = SipfragStatus.parse(status.toBody());

        assertEquals(status, parsed);
        assertFalse(parsed.isFinal());
        assertTrue(new SipfragStatus(603, "Decline").isFinal());
    }

    @Test
    void rejectsMalformedSipfragStatusLine() {
        assertThrows(IllegalArgumentException.class, () -> SipfragStatus.parse(
                SipBody.of("SIP/2.0 200 OK".getBytes(StandardCharsets.US_ASCII))
        ));
        assertThrows(IllegalArgumentException.class, () -> SipfragStatus.parse(
                SipBody.of("SIP/2.0 99 Broken\r\n".getBytes(StandardCharsets.US_ASCII))
        ));
    }
}
