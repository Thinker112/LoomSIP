package org.loomsip.transaction.invite;

import org.loomsip.message.SipBody;
import org.loomsip.message.SipHeader;
import org.loomsip.message.SipHeaders;
import org.loomsip.message.SipMethod;
import org.loomsip.message.SipRequest;
import org.loomsip.message.header.CSeqHeaderValue;
import org.loomsip.message.header.SipHeaderValueException;
import org.loomsip.message.header.SipHeaderValues;

import java.util.Objects;

/** Creates CANCEL requests related to an active INVITE client transaction. */
public final class InviteCancellations {

    private InviteCancellations() {
    }

    /**
     * Creates CANCEL with the original INVITE branch, route, and CSeq number.
     *
     * @param invite original INVITE
     * @return empty-body CANCEL request
     */
    public static SipRequest createCancel(SipRequest invite) {
        Objects.requireNonNull(invite, "invite");
        if (!SipMethod.INVITE.equals(invite.method())) {
            throw new IllegalArgumentException("CANCEL source request must be INVITE");
        }
        try {
            CSeqHeaderValue cseq = SipHeaderValues.cseq(invite.headers());
            SipHeaders.Builder headers = SipHeaders.builder()
                    .add(required(invite, "Via"));
            invite.headers().first("Max-Forwards").ifPresent(headers::add);
            headers.add(required(invite, "From"));
            headers.add(required(invite, "To"));
            headers.add(required(invite, "Call-ID"));
            headers.add("CSeq", cseq.sequenceNumber() + " CANCEL");
            headers.addAll(invite.headers().all("Route"));
            return new SipRequest(
                    SipMethod.CANCEL,
                    invite.requestUri(),
                    invite.version(),
                    headers.build(),
                    SipBody.empty()
            );
        } catch (SipHeaderValueException exception) {
            throw new IllegalArgumentException("INVITE routing headers are malformed", exception);
        }
    }

    private static SipHeader required(SipRequest invite, String name) {
        return invite.headers().first(name).orElseThrow(
                () -> new IllegalArgumentException("INVITE has no " + name + " header")
        );
    }
}
