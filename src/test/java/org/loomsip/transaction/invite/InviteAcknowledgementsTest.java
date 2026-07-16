package org.loomsip.transaction.invite;

import org.junit.jupiter.api.Test;
import org.loomsip.message.SipHeaders;
import org.loomsip.message.SipMethod;
import org.loomsip.message.SipRequest;
import org.loomsip.message.SipResponse;
import org.loomsip.message.SipResponses;
import org.loomsip.message.SipUri;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InviteAcknowledgementsTest {

    @Test
    void createsNon2xxAckWithInviteBranchAndResponseToTag() {
        SipRequest invite = invite();
        SipResponse response = SipResponses.createResponse(invite, 486, "Busy Here", "server-tag");

        SipRequest ack = InviteAcknowledgements.createNon2xxAck(invite, response);

        assertEquals(SipMethod.ACK, ack.method());
        assertEquals(invite.requestUri(), ack.requestUri());
        assertEquals(invite.headers().firstValue("Via"), ack.headers().firstValue("Via"));
        assertEquals("9 ACK", ack.headers().firstValue("CSeq").orElseThrow());
        assertEquals("<sip:bob@example.com>;tag=server-tag", ack.headers().firstValue("To").orElseThrow());
        assertEquals(List.of("<sip:proxy.example.com;lr>"),
                ack.headers().all("Route").stream().map(header -> header.value()).toList());
        assertTrue(ack.body().isEmpty());
    }

    @Test
    void rejectsAckFor2xxResponse() {
        SipRequest invite = invite();
        SipResponse response = SipResponses.createResponse(invite, 200, "OK", "server-tag");

        assertThrows(IllegalArgumentException.class,
                () -> InviteAcknowledgements.createNon2xxAck(invite, response));
    }

    private static SipRequest invite() {
        SipHeaders headers = SipHeaders.builder()
                .add("Via", "SIP/2.0/UDP 127.0.0.1:5060;branch=z9hG4bK-ack-test;rport")
                .add("Max-Forwards", "70")
                .add("From", "<sip:alice@example.com>;tag=client-tag")
                .add("To", "<sip:bob@example.com>")
                .add("Call-ID", "ack-test@example.com")
                .add("CSeq", "9 INVITE")
                .add("Route", "<sip:proxy.example.com;lr>")
                .build();
        return new SipRequest(SipMethod.INVITE, SipUri.parse("sip:bob@example.com"), headers);
    }
}
