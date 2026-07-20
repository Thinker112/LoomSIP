package org.loomsip.message.header;

import org.junit.jupiter.api.Test;
import org.loomsip.message.SipHeaders;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SessionTimerHeaderValuesTest {

    @Test
    void parsesSessionExpiresAndMinSe() throws Exception {
        SipHeaders headers = SipHeaders.builder()
                .add("Session-Expires", "1800;refresher=uac")
                .add("Min-SE", "90")
                .build();

        SessionExpiresHeaderValue expires = SipHeaderValues.sessionExpires(headers);
        assertEquals(1800, expires.intervalSeconds());
        assertEquals(SessionRefresher.UAC, expires.refresher().orElseThrow());
        assertEquals("1800;refresher=uac", expires.wireValue());
        assertEquals("90", SipHeaderValues.minSe(headers).wireValue());
    }

    @Test
    void rejectsInvalidSessionTimerHeaders() {
        assertThrows(SipHeaderValueException.class, () -> SipHeaderValues.sessionExpires(
                SipHeaders.builder().add("Session-Expires", "0").build()
        ));
        assertThrows(SipHeaderValueException.class, () -> SipHeaderValues.sessionExpires(
                SipHeaders.builder().add("Session-Expires", "120;refresher=proxy").build()
        ));
        assertThrows(SipHeaderValueException.class, () -> SipHeaderValues.minSe(
                SipHeaders.builder().add("Min-SE", "bad").build()
        ));
    }
}
