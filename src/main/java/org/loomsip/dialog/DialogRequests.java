package org.loomsip.dialog;

import org.loomsip.message.SipBody;
import org.loomsip.message.SipHeader;
import org.loomsip.message.SipHeaders;
import org.loomsip.message.SipMethod;
import org.loomsip.message.SipRequest;
import org.loomsip.message.SipVersion;
import org.loomsip.message.header.SipParameter;
import org.loomsip.message.header.ViaHeaderValue;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Creates immutable requests from Dialog identity, route, and sequence state. */
final class DialogRequests {

    private static final List<String> MANAGED_HEADERS = List.of(
            "Via", "Max-Forwards", "From", "To", "Call-ID", "CSeq", "Route", "Content-Length"
    );

    private DialogRequests() {
    }

    /**
     * Creates one request using a sequence number already allocated by the Dialog.
     *
     * @param dialog current confirmed Dialog
     * @param routePlan request routing plan
     * @param profile local Via and transport profile
     * @param method request method
     * @param sequenceNumber allocated local CSeq
     * @param additionalHeaders application headers not managed by Dialog routing
     * @param body immutable request body
     * @param branch new RFC 3261 transaction branch
     * @return immutable request and next hop
     */
    static DialogPreparedRequest create(
            DialogSnapshot dialog,
            DialogRoutePlan routePlan,
            DialogRequestProfile profile,
            SipMethod method,
            long sequenceNumber,
            SipHeaders additionalHeaders,
            SipBody body,
            String branch
    ) {
        Objects.requireNonNull(dialog, "dialog");
        Objects.requireNonNull(routePlan, "routePlan");
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(additionalHeaders, "additionalHeaders");
        Objects.requireNonNull(body, "body");
        Objects.requireNonNull(branch, "branch");
        if (dialog.state() != DialogState.CONFIRMED) {
            throw new IllegalArgumentException("in-Dialog request requires a confirmed Dialog");
        }
        if (dialog.secure() && profile.preferredTransport() != org.loomsip.transport.TransportProtocol.TLS) {
            throw new IllegalArgumentException("secure Dialog requests require TLS transport");
        }
        if (!SipMethod.INVITE.equals(method) && !SipMethod.BYE.equals(method)) {
            throw new IllegalArgumentException("4D supports only re-INVITE and BYE");
        }
        rejectManagedHeaders(additionalHeaders);

        List<SipParameter> viaParameters = new ArrayList<>();
        viaParameters.add(new SipParameter("branch", java.util.Optional.of(branch)));
        if (profile.useRPort()) {
            viaParameters.add(new SipParameter("rport", java.util.Optional.empty()));
        }
        ViaHeaderValue via = new ViaHeaderValue(
                profile.viaTransport(),
                profile.sentBy(),
                viaParameters
        );
        SipHeaders.Builder headers = SipHeaders.builder()
                .add("Via", via.wireValue())
                .add("Max-Forwards", "70")
                .add("From", taggedAddress(dialog.localUri().toString(), dialog.id().localTag()))
                .add("To", taggedAddress(dialog.remoteUri().toString(), dialog.id().remoteTag()))
                .add("Call-ID", dialog.id().callId())
                .add("CSeq", sequenceNumber + " " + method.value());
        routePlan.routes().forEach(route -> headers.add("Route", route.wireValue()));
        headers.addAll(additionalHeaders.entries());
        return new DialogPreparedRequest(
                new SipRequest(
                        method,
                        routePlan.requestUri(),
                        SipVersion.SIP_2_0,
                        headers.build(),
                        body
                ),
                routePlan.nextHop()
        );
    }

    private static void rejectManagedHeaders(SipHeaders headers) {
        for (SipHeader header : headers.entries()) {
            if (MANAGED_HEADERS.stream().anyMatch(name -> SipHeaders.namesEqual(name, header.name()))) {
                throw new IllegalArgumentException("Dialog manages header: " + header.name());
            }
        }
    }

    private static String taggedAddress(String uri, String tag) {
        return "<" + uri + ">;tag=" + tag;
    }
}
