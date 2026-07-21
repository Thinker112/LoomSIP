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

/** Builds and starts UAS NOTIFY transactions for one existing Subscription. */
public final class SubscriptionPublisher {

    private final NonInviteTransactionManager transactions;
    private final SubscriptionRequestProfile profile;
    private final Supplier<String> branchGenerator;

    /** Creates a publisher over an existing NICT manager and local request profile. */
    public SubscriptionPublisher(NonInviteTransactionManager transactions, SubscriptionRequestProfile profile,
                                 Supplier<String> branchGenerator) {
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.profile = Objects.requireNonNull(profile, "profile");
        this.branchGenerator = Objects.requireNonNull(branchGenerator, "branchGenerator");
    }

    /** Builds and starts one managed NOTIFY transaction. */
    public ClientTransactionHandle publish(SubscriptionNotification notification) throws TransactionKeyException {
        Objects.requireNonNull(notification, "notification");
        rejectManagedHeaders(notification.additionalHeaders());
        List<SipParameter> parameters = new ArrayList<>();
        parameters.add(new SipParameter("branch", java.util.Optional.of(
                Objects.requireNonNull(branchGenerator.get(), "branchGenerator result")
        )));
        if (profile.useRPort()) {
            parameters.add(new SipParameter("rport", java.util.Optional.empty()));
        }
        SubscriptionId id = notification.id();
        SipHeaders.Builder headers = SipHeaders.builder()
                .add("Via", new ViaHeaderValue(profile.viaTransport(), profile.sentBy(), parameters).wireValue())
                .add("Max-Forwards", "70")
                .add("From", "<" + notification.localUri() + ">;tag=" + id.localTag())
                .add("To", "<" + notification.remoteUri() + ">;tag=" + id.remoteTag())
                .add("Call-ID", id.callId())
                .add("CSeq", notification.cseq() + " NOTIFY")
                .add("Event", id.event().wireValue())
                .add("Subscription-State", notification.state().wireValue());
        notification.additionalHeaders().entries().forEach(header -> headers.add(header.name(), header.value()));
        return transactions.sendRequest(new SipRequest(SipMethod.NOTIFY, notification.requestUri(), SipVersion.SIP_2_0,
                headers.build(), notification.body()), notification.target());
    }

    private static void rejectManagedHeaders(SipHeaders headers) {
        List<String> managed = List.of("Via", "Max-Forwards", "From", "To", "Call-ID", "CSeq", "Event", "Subscription-State");
        if (headers.entries().stream().anyMatch(header -> managed.stream().anyMatch(name -> SipHeaders.namesEqual(name, header.name())))) {
            throw new IllegalArgumentException("SubscriptionPublisher manages routing and subscription headers");
        }
    }
}
