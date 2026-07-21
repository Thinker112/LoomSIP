package org.loomsip.refer;

import org.loomsip.message.SipHeaders;
import org.loomsip.message.header.SubscriptionState;
import org.loomsip.subscription.SubscriptionNotification;
import org.loomsip.subscription.SubscriptionPublisher;
import org.loomsip.transaction.TransactionKeyException;
import org.loomsip.transaction.noninvite.ClientTransactionHandle;

import java.util.Objects;

/**
 * RFC 3515 publisher boundary for refer event package NOTIFY messages.
 *
 * <pre>{@code
 * transfer status --> SipfragStatus --> ReferNotifier --> SubscriptionPublisher --> NICT
 * }</pre>
 *
 * <p>This type owns the refer-specific Event package validation and
 * {@code message/sipfrag} Content-Type. The generic publisher continues to
 * own Via, dialog identity, CSeq and Subscription-State serialization.</p>
 */
public final class ReferNotifier {

    private final SubscriptionPublisher publisher;

    /**
     * Creates a refer NOTIFY publisher over the generic UAS Subscription publisher.
     *
     * @param publisher generic UAS NOTIFY publisher
     */
    public ReferNotifier(SubscriptionPublisher publisher) {
        this.publisher = Objects.requireNonNull(publisher, "publisher");
    }

    /**
     * Builds and starts one refer NOTIFY with a mandatory message/sipfrag payload.
     *
     * @param notification generic routing and Subscription-State context; its body is ignored
     * @param status referred-request progress status encoded as message/sipfrag
     * @return started NOTIFY client transaction
     * @throws TransactionKeyException if the underlying NICT cannot be created
     * @throws IllegalArgumentException if the context is not a coherent refer notification
     */
    public ClientTransactionHandle publish(SubscriptionNotification notification, SipfragStatus status)
            throws TransactionKeyException {
        Objects.requireNonNull(notification, "notification");
        Objects.requireNonNull(status, "status");
        if (!"refer".equals(notification.id().event().normalizedPackageName())
                || notification.id().event().eventId().isPresent()) {
            throw new IllegalArgumentException("ReferNotifier requires Event: refer without an event-id");
        }
        boolean terminated = notification.state().state() == SubscriptionState.TERMINATED;
        if (status.isFinal() != terminated) {
            throw new IllegalArgumentException("final sipfrag status must match terminated Subscription-State");
        }
        if (!notification.additionalHeaders().all("Content-Type").isEmpty()) {
            throw new IllegalArgumentException("ReferNotifier manages Content-Type");
        }
        SipHeaders headers = notification.additionalHeaders().toBuilder().add("Content-Type", "message/sipfrag").build();
        return publisher.publish(new SubscriptionNotification(
                notification.id(), notification.requestUri(), notification.localUri(), notification.remoteUri(),
                notification.cseq(), notification.state(), headers, status.toBody(), notification.target()
        ));
    }
}
