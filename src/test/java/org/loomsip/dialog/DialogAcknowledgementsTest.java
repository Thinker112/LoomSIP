package org.loomsip.dialog;

import org.junit.jupiter.api.Test;
import org.loomsip.message.SipHeaders;
import org.loomsip.message.SipMethod;
import org.loomsip.message.SipRequest;
import org.loomsip.message.SipResponse;
import org.loomsip.message.SipResponses;
import org.loomsip.message.SipUri;
import org.loomsip.message.header.RouteHeaderValue;
import org.loomsip.message.header.SipHeaderValues;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DialogAcknowledgementsTest {

    @Test
    void createsTransactionIndependentAckFromDialogRoutePlan() throws Exception {
        SipRequest invite = invite();
        SipResponse response = success(invite, "remote-tag");
        DialogSnapshot dialog = snapshot(
                "remote-tag",
                List.of(RouteHeaderValue.of(SipUri.parse("sip:edge.example.com;lr")))
        );

        DialogAckTransmission transmission = DialogAcknowledgements.create2xxAck(
                dialog,
                invite,
                response,
                "z9hG4bK-dialog-ack-1"
        );
        SipRequest ack = transmission.ack();

        assertEquals(SipMethod.ACK, ack.method());
        assertEquals(SipUri.parse("sip:bob@target.example.com"), ack.requestUri());
        assertEquals(SipUri.parse("sip:edge.example.com;lr"), transmission.nextHop());
        assertEquals("1 ACK", ack.headers().firstValue("CSeq").orElseThrow());
        assertEquals(response.headers().firstValue("To"), ack.headers().firstValue("To"));
        assertEquals(List.of("<sip:edge.example.com;lr>"),
                ack.headers().all("Route").stream().map(header -> header.value()).toList());
        assertEquals("z9hG4bK-dialog-ack-1",
                SipHeaderValues.topVia(ack.headers()).branch().orElseThrow());
        assertTrue(SipHeaderValues.topVia(ack.headers()).hasRPort());
        assertNotEquals(
                SipHeaderValues.topVia(invite.headers()).branch(),
                SipHeaderValues.topVia(ack.headers()).branch()
        );
    }

    @Test
    void appliesStrictRoutingToAck() {
        SipRequest invite = invite();
        SipResponse response = success(invite, "remote-tag");
        DialogSnapshot dialog = snapshot(
                "remote-tag",
                List.of(
                        RouteHeaderValue.of(SipUri.parse("sip:strict.example.com")),
                        RouteHeaderValue.of(SipUri.parse("sip:loose.example.com;lr"))
                )
        );

        DialogAckTransmission transmission = DialogAcknowledgements.create2xxAck(
                dialog,
                invite,
                response,
                "z9hG4bK-dialog-ack-2"
        );

        assertEquals(SipUri.parse("sip:strict.example.com"), transmission.ack().requestUri());
        assertEquals(List.of(
                "<sip:loose.example.com;lr>",
                "<sip:bob@target.example.com>"
        ), transmission.ack().headers().all("Route").stream()
                .map(header -> header.value()).toList());
    }

    @Test
    void rejectsResponseFromAnotherDialog() {
        SipRequest invite = invite();
        SipResponse response = success(invite, "other-tag");

        assertThrows(IllegalArgumentException.class, () ->
                DialogAcknowledgements.create2xxAck(
                        snapshot("remote-tag", List.of()),
                        invite,
                        response,
                        "z9hG4bK-dialog-ack-3"
                ));
    }

    private static SipRequest invite() {
        return new SipRequest(
                SipMethod.INVITE,
                SipUri.parse("sip:bob@example.com"),
                SipHeaders.builder()
                        .add("Via", "SIP/2.0/UDP client.example.com:5060"
                                + ";branch=z9hG4bK-original;rport")
                        .add("Max-Forwards", "70")
                        .add("From", "<sip:alice@example.com>;tag=local-tag")
                        .add("To", "<sip:bob@example.com>")
                        .add("Call-ID", "ack-test@example.com")
                        .add("CSeq", "1 INVITE")
                        .build()
        );
    }

    private static SipResponse success(SipRequest invite, String remoteTag) {
        SipResponse base = SipResponses.createResponse(invite, 200, "OK", remoteTag);
        return new SipResponse(
                base.version(),
                base.statusCode(),
                base.reasonPhrase(),
                base.headers().toBuilder()
                        .add("Contact", "<sip:bob@target.example.com>")
                        .build(),
                base.body()
        );
    }

    private static DialogSnapshot snapshot(
            String remoteTag,
            List<RouteHeaderValue> routeSet
    ) {
        return new DialogSnapshot(
                new DialogId("ack-test@example.com", "local-tag", remoteTag),
                DialogRole.UAC,
                DialogState.CONFIRMED,
                SipUri.parse("sip:alice@example.com"),
                SipUri.parse("sip:bob@example.com"),
                1,
                0,
                routeSet,
                Optional.of(SipUri.parse("sip:bob@target.example.com")),
                false
        );
    }
}
