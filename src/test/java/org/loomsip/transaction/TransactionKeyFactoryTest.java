package org.loomsip.transaction;

import org.junit.jupiter.api.Test;
import org.loomsip.message.SipHeaders;
import org.loomsip.message.SipMethod;
import org.loomsip.message.SipRequest;
import org.loomsip.message.SipResponse;
import org.loomsip.message.SipUri;
import org.loomsip.message.header.SentBy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TransactionKeyFactoryTest {

    @Test
    void normalizesModernBranchAndDefaultSentByPort() throws Exception {
        SipRequest implicitPort = request(SipMethod.OPTIONS, "SIP/2.0/UDP EXAMPLE.com;branch=z9hG4bK-1");
        SipRequest explicitPort = request(SipMethod.OPTIONS, "SIP/2.0/UDP example.COM:5060;branch=z9hG4bK-1");

        TransactionKey first = TransactionKeyFactory.fromRequest(implicitPort);
        TransactionKey second = TransactionKeyFactory.fromRequest(explicitPort);

        assertEquals(first, second);
        Rfc3261TransactionKey key = assertInstanceOf(Rfc3261TransactionKey.class, first);
        assertEquals(new SentBy("example.com", 5060), key.sentBy());
        assertEquals(SipMethod.OPTIONS, key.method());
    }

    @Test
    void derivesAckAndCancelInviteRelationshipsWithoutChangingOwnKeys() throws Exception {
        SipRequest ack = request(SipMethod.ACK, "SIP/2.0/UDP host:5060;branch=z9hG4bK-invite");
        SipRequest cancel = request(SipMethod.CANCEL, "SIP/2.0/UDP host:5060;branch=z9hG4bK-invite");

        assertEquals(SipMethod.ACK, TransactionKeyFactory.fromRequest(ack).method());
        assertEquals(SipMethod.INVITE, TransactionKeyFactory.forServerLookup(ack).method());
        assertEquals(SipMethod.CANCEL, TransactionKeyFactory.fromRequest(cancel).method());
        assertEquals(SipMethod.INVITE, TransactionKeyFactory.relatedInvite(cancel).method());
        assertNotEquals(TransactionKeyFactory.fromRequest(cancel), TransactionKeyFactory.relatedInvite(cancel));
    }

    @Test
    void createsIsolatedLegacyCompositeKey() throws Exception {
        SipRequest request = request(
                SipMethod.OPTIONS,
                "SIP/2.0/UDP legacy.example.com, SIP/2.0/TCP backup.example.com"
        );

        LegacyTransactionKey key = assertInstanceOf(
                LegacyTransactionKey.class,
                TransactionKeyFactory.fromRequest(request)
        );

        assertEquals("call@example.com", key.callId());
        assertEquals("from-tag", key.fromTag().orElseThrow());
        assertEquals(7, key.sequenceNumber());
        assertEquals("SIP/2.0/UDP legacy.example.com", key.topViaValue());
    }

    @Test
    void derivesClientKeyFromResponseAndRejectsInconsistentRequest() throws Exception {
        SipHeaders responseHeaders = headers(
                SipMethod.OPTIONS,
                "SIP/2.0/UDP host;branch=z9hG4bK-response"
        );
        SipResponse response = new SipResponse(200, "OK", responseHeaders);

        Rfc3261TransactionKey key = TransactionKeyFactory.fromResponse(response);
        assertEquals(SipMethod.OPTIONS, key.method());

        SipRequest inconsistent = new SipRequest(
                SipMethod.OPTIONS,
                SipUri.parse("sip:bob@example.com"),
                headers(SipMethod.INVITE, "SIP/2.0/UDP host;branch=z9hG4bK-bad")
        );
        assertThrows(TransactionKeyException.class,
                () -> TransactionKeyFactory.fromRequest(inconsistent));
    }

    private static SipRequest request(SipMethod method, String via) {
        return new SipRequest(
                method,
                SipUri.parse("sip:bob@example.com"),
                headers(method, via)
        );
    }

    private static SipHeaders headers(SipMethod method, String via) {
        return SipHeaders.builder()
                .add("Via", via)
                .add("From", "<sip:alice@example.com>;tag=from-tag")
                .add("To", "<sip:bob@example.com>")
                .add("Call-ID", "call@example.com")
                .add("CSeq", "7 " + method)
                .build();
    }
}
