package org.loomsip.subscription;

import org.loomsip.message.header.SentBy;
import org.loomsip.message.header.ViaTransport;
import org.loomsip.transport.TransportProtocol;

import java.util.Objects;

/** Local Via identity used to construct initial SUBSCRIBE requests. */
public record SubscriptionRequestProfile(
        ViaTransport viaTransport,
        SentBy sentBy,
        TransportProtocol protocol,
        boolean useRPort
) {
    /** Validates Via and selected transport consistency. */
    public SubscriptionRequestProfile {
        Objects.requireNonNull(viaTransport, "viaTransport");
        Objects.requireNonNull(sentBy, "sentBy");
        Objects.requireNonNull(protocol, "protocol");
        if (!viaTransport.value().equals(protocol.name()) || useRPort && protocol != TransportProtocol.UDP) {
            throw new IllegalArgumentException("invalid Subscription request transport profile");
        }
    }
}
