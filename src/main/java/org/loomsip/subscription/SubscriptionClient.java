package org.loomsip.subscription;

import org.loomsip.message.SipHeaders;
import org.loomsip.message.SipMethod;
import org.loomsip.message.SipRequest;
import org.loomsip.message.SipVersion;
import org.loomsip.message.header.SipParameter;
import org.loomsip.message.header.ViaHeaderValue;
import org.loomsip.transaction.TransactionKeyException;
import org.loomsip.transaction.noninvite.ClientTransactionHandle;
import org.loomsip.transaction.noninvite.NonInviteTransactionManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/** Constructs and starts initial out-of-dialog SUBSCRIBE client transactions. */
public final class SubscriptionClient {

    private final NonInviteTransactionManager transactions;
    private final SubscriptionRequestProfile profile;
    private final Supplier<String> branchGenerator;

    /** Creates a client over an existing NICT manager and local request profile. */
    public SubscriptionClient(
            NonInviteTransactionManager transactions,
            SubscriptionRequestProfile profile,
            Supplier<String> branchGenerator
    ) {
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.profile = Objects.requireNonNull(profile, "profile");
        this.branchGenerator = Objects.requireNonNull(branchGenerator, "branchGenerator");
    }

    /** Builds and starts one initial SUBSCRIBE using managed routing headers. */
    public ClientTransactionHandle subscribe(InitialSubscriptionRequest request) throws TransactionKeyException {
        Objects.requireNonNull(request, "request");
        rejectManagedHeaders(request.additionalHeaders());
        String branch = Objects.requireNonNull(branchGenerator.get(), "branchGenerator result");
        List<SipParameter> parameters = new ArrayList<>();
        parameters.add(new SipParameter("branch", java.util.Optional.of(branch)));
        if (profile.useRPort()) {
            parameters.add(new SipParameter("rport", java.util.Optional.empty()));
        }
        SipHeaders.Builder headers = SipHeaders.builder()
                .add("Via", new ViaHeaderValue(profile.viaTransport(), profile.sentBy(), parameters).wireValue())
                .add("Max-Forwards", "70")
                .add("From", "<" + request.localUri() + ">;tag=" + request.localTag())
                .add("To", "<" + request.remoteUri() + ">")
                .add("Call-ID", request.callId())
                .add("CSeq", request.cseq() + " SUBSCRIBE")
                .add("Event", request.event().wireValue())
                .add("Expires", request.expires().wireValue());
        request.additionalHeaders().entries().forEach(header -> headers.add(header.name(), header.value()));
        return transactions.sendRequest(new SipRequest(
                SipMethod.SUBSCRIBE, request.requestUri(), SipVersion.SIP_2_0, headers.build(), request.body()
        ), request.target());
    }

    /**
     * Builds and starts one in-dialog SUBSCRIBE refresh or Expires: 0 cancellation.
     *
     * @param request immutable refresh identity, route, CSeq, and expiry parameters
     * @return started refresh client transaction
     * @throws TransactionKeyException if the NICT cannot be created
     */
    public ClientTransactionHandle refresh(SubscriptionRefreshRequest request) throws TransactionKeyException {
        Objects.requireNonNull(request, "request");
        rejectManagedHeaders(request.additionalHeaders());
        String branch = Objects.requireNonNull(branchGenerator.get(), "branchGenerator result");
        List<SipParameter> parameters = new ArrayList<>();
        parameters.add(new SipParameter("branch", java.util.Optional.of(branch)));
        if (profile.useRPort()) {
            parameters.add(new SipParameter("rport", java.util.Optional.empty()));
        }
        SubscriptionId id = request.id();
        SipHeaders.Builder headers = SipHeaders.builder()
                .add("Via", new ViaHeaderValue(profile.viaTransport(), profile.sentBy(), parameters).wireValue())
                .add("Max-Forwards", "70")
                .add("From", "<" + request.localUri() + ">;tag=" + id.localTag())
                .add("To", "<" + request.remoteUri() + ">;tag=" + id.remoteTag())
                .add("Call-ID", id.callId())
                .add("CSeq", request.cseq() + " SUBSCRIBE")
                .add("Event", id.event().wireValue())
                .add("Expires", request.expires().wireValue());
        request.additionalHeaders().entries().forEach(header -> headers.add(header.name(), header.value()));
        return transactions.sendRequest(new SipRequest(
                SipMethod.SUBSCRIBE, request.requestUri(), SipVersion.SIP_2_0, headers.build(), request.body()
        ), request.target());
    }

    private static void rejectManagedHeaders(SipHeaders headers) {
        List<String> managed = List.of("Via", "Max-Forwards", "From", "To", "Call-ID", "CSeq", "Event", "Expires");
        if (headers.entries().stream().anyMatch(header -> managed.stream().anyMatch(name -> SipHeaders.namesEqual(name, header.name())))) {
            throw new IllegalArgumentException("SubscriptionClient manages routing and subscription headers");
        }
    }
}
