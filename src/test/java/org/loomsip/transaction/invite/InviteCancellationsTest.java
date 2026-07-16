package org.loomsip.transaction.invite;

import org.junit.jupiter.api.Test;
import org.loomsip.message.SipHeaders;
import org.loomsip.message.SipMethod;
import org.loomsip.message.SipRequest;
import org.loomsip.message.SipUri;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InviteCancellationsTest {

    @Test
    void preservesInviteIdentityWhileChangingCSeqMethod() {
        SipRequest invite = invite();

        SipRequest cancel = InviteCancellations.createCancel(invite);

        assertEquals(SipMethod.CANCEL, cancel.method());
        assertEquals(invite.requestUri(), cancel.requestUri());
        assertEquals(invite.headers().firstValue("Via"), cancel.headers().firstValue("Via"));
        assertEquals(invite.headers().firstValue("Call-ID"), cancel.headers().firstValue("Call-ID"));
        assertEquals("17 CANCEL", cancel.headers().firstValue("CSeq").orElseThrow());
        assertTrue(cancel.body().isEmpty());
    }

    private static SipRequest invite() {
        return new SipRequest(
                SipMethod.INVITE,
                SipUri.parse("sip:bob@example.com"),
                SipHeaders.builder()
                        .add("Via", "SIP/2.0/UDP 127.0.0.1:5060;branch=z9hG4bK-cancel-test;rport")
                        .add("Max-Forwards", "70")
                        .add("From", "<sip:alice@example.com>;tag=client-tag")
                        .add("To", "<sip:bob@example.com>")
                        .add("Call-ID", "cancel-test@example.com")
                        .add("CSeq", "17 INVITE")
                        .build()
        );
    }
}
