package org.loomsip.transaction.invite;

import org.loomsip.message.SipHeader;
import org.loomsip.message.SipHeaders;
import org.loomsip.message.SipBody;
import org.loomsip.message.SipMethod;
import org.loomsip.message.SipRequest;
import org.loomsip.message.SipResponse;
import org.loomsip.message.header.CSeqHeaderValue;
import org.loomsip.message.header.SipHeaderValueException;
import org.loomsip.message.header.SipHeaderValues;

import java.util.Objects;

/** Creates transaction-layer ACK requests for non-2xx INVITE responses. */
public final class InviteAcknowledgements {

    private InviteAcknowledgements() {
    }

    /**
     * Creates a non-2xx ACK using the original INVITE branch and routing fields.
     *
     * <p>The To header comes from the final response so its remote tag is retained.
     * Route fields and Max-Forwards are copied from the INVITE when present.</p>
     *
     * @param invite original INVITE
     * @param response correlated 300-699 final response
     * @return empty-body ACK request
     */
    public static SipRequest createNon2xxAck(SipRequest invite, SipResponse response) {
        Objects.requireNonNull(invite, "invite");
        Objects.requireNonNull(response, "response");
        if (!SipMethod.INVITE.equals(invite.method())) {
            throw new IllegalArgumentException("ACK source request must be INVITE");
        }
        if (response.statusCode() < 300) {
            throw new IllegalArgumentException("transaction ACK requires a 300-699 response");
        }

        try {
            CSeqHeaderValue inviteCSeq = SipHeaderValues.cseq(invite.headers());
            CSeqHeaderValue responseCSeq = SipHeaderValues.cseq(response.headers());
            String inviteCallId = SipHeaderValues.callId(invite.headers());
            String responseCallId = SipHeaderValues.callId(response.headers());
            if (!inviteCSeq.equals(responseCSeq) || !inviteCallId.equals(responseCallId)) {
                throw new IllegalArgumentException("response does not correlate to the INVITE");
            }

            SipHeaders.Builder headers = SipHeaders.builder()
                    .add(required(invite, "Via"));
            invite.headers().first("Max-Forwards").ifPresent(headers::add);
            headers.add(required(invite, "From"));
            headers.add(required(response, "To"));
            headers.add(required(invite, "Call-ID"));
            headers.add("CSeq", inviteCSeq.sequenceNumber() + " ACK");
            headers.addAll(invite.headers().all("Route"));
            return new SipRequest(SipMethod.ACK, invite.requestUri(), invite.version(), headers.build(), SipBody.empty());
        } catch (SipHeaderValueException exception) {
            throw new IllegalArgumentException("INVITE or response routing headers are malformed", exception);
        }
    }

    private static SipHeader required(SipRequest message, String name) {
        return message.headers().first(name).orElseThrow(
                () -> new IllegalArgumentException("INVITE has no " + name + " header")
        );
    }

    private static SipHeader required(SipResponse message, String name) {
        return message.headers().first(name).orElseThrow(
                () -> new IllegalArgumentException("response has no " + name + " header")
        );
    }
}
