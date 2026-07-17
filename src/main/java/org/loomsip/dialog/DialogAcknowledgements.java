package org.loomsip.dialog;

import org.loomsip.message.SipBody;
import org.loomsip.message.SipHeader;
import org.loomsip.message.SipHeaders;
import org.loomsip.message.SipMethod;
import org.loomsip.message.SipRequest;
import org.loomsip.message.SipResponse;
import org.loomsip.message.header.CSeqHeaderValue;
import org.loomsip.message.header.SipHeaderValueException;
import org.loomsip.message.header.SipHeaderValues;
import org.loomsip.message.header.SipParameter;
import org.loomsip.message.header.ViaHeaderValue;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Creates transaction-independent ACK requests for successful INVITE responses. */
public final class DialogAcknowledgements {

    private DialogAcknowledgements() {
    }

    /**
     * Creates an ACK from a confirmed UAC Dialog and its INVITE success response.
     *
     * @param dialog confirmed UAC Dialog state
     * @param invite original INVITE
     * @param response correlated 2xx response
     * @param branch new RFC 3261 Via branch
     * @return immutable ACK and SIP next hop
     */
    public static DialogAckTransmission create2xxAck(
            DialogSnapshot dialog,
            SipRequest invite,
            SipResponse response,
            String branch
    ) {
        Objects.requireNonNull(dialog, "dialog");
        Objects.requireNonNull(invite, "invite");
        Objects.requireNonNull(response, "response");
        Objects.requireNonNull(branch, "branch");
        if (dialog.role() != DialogRole.UAC || dialog.state() != DialogState.CONFIRMED) {
            throw new IllegalArgumentException("2xx ACK requires a confirmed UAC Dialog");
        }
        if (!SipMethod.INVITE.equals(invite.method())
                || response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalArgumentException("2xx ACK requires INVITE and 200-299 response");
        }

        try {
            CSeqHeaderValue inviteCSeq = SipHeaderValues.cseq(invite.headers());
            CSeqHeaderValue responseCSeq = SipHeaderValues.cseq(response.headers());
            if (!inviteCSeq.equals(responseCSeq)
                    || !SipHeaderValues.callId(invite.headers()).equals(
                            SipHeaderValues.callId(response.headers())
                    )
                    || !dialog.id().equals(DialogId.from(response.headers(), DialogRole.UAC))) {
                throw new IllegalArgumentException("response does not correlate to the UAC Dialog");
            }

            DialogRoutePlan routePlan = new DialogRoutePlanner().plan(dialog);
            SipHeaders.Builder headers = SipHeaders.builder()
                    .add("Via", ackVia(invite, branch));
            invite.headers().first("Max-Forwards")
                    .ifPresentOrElse(headers::add, () -> headers.add("Max-Forwards", "70"));
            headers.add(required(invite, "From"));
            headers.add(required(response, "To"));
            headers.add(required(invite, "Call-ID"));
            headers.add("CSeq", inviteCSeq.sequenceNumber() + " ACK");
            routePlan.routes().forEach(route -> headers.add("Route", route.wireValue()));
            SipRequest ack = new SipRequest(
                    SipMethod.ACK,
                    routePlan.requestUri(),
                    invite.version(),
                    headers.build(),
                    SipBody.empty()
            );
            return new DialogAckTransmission(ack, routePlan.nextHop());
        } catch (SipHeaderValueException exception) {
            throw new IllegalArgumentException("INVITE or response Dialog headers are malformed", exception);
        }
    }

    private static String ackVia(SipRequest invite, String branch) throws SipHeaderValueException {
        ViaHeaderValue original = SipHeaderValues.topVia(invite.headers());
        List<SipParameter> parameters = new ArrayList<>();
        parameters.add(new SipParameter("branch", Optional.of(branch)));
        for (SipParameter parameter : original.parameters()) {
            if (parameter.name().equals("branch") || parameter.name().equals("received")) {
                continue;
            }
            if (parameter.name().equals("rport")) {
                parameters.add(new SipParameter("rport", Optional.empty()));
            } else {
                parameters.add(parameter);
            }
        }
        return new ViaHeaderValue(original.transport(), original.sentBy(), List.copyOf(parameters)).wireValue();
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
