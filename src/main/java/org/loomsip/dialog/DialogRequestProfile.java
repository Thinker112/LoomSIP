package org.loomsip.dialog;

import org.loomsip.message.header.SentBy;
import org.loomsip.message.header.ViaTransport;
import org.loomsip.transport.TransportProtocol;

import java.util.Objects;

/**
 * Local network identity used to construct requests inside a Dialog.
 *
 * @param viaTransport transport token written to Via
 * @param sentBy local Via sent-by host and port
 * @param preferredTransport transport preference passed to target resolution
 * @param useRPort whether UDP requests include the flag-form rport parameter
 */
public record DialogRequestProfile(
        ViaTransport viaTransport,
        SentBy sentBy,
        TransportProtocol preferredTransport,
        boolean useRPort
) {

    /** Validates transport consistency and local identity. */
    public DialogRequestProfile {
        Objects.requireNonNull(viaTransport, "viaTransport");
        Objects.requireNonNull(sentBy, "sentBy");
        Objects.requireNonNull(preferredTransport, "preferredTransport");
        if (!viaTransport.value().equals(preferredTransport.name())) {
            throw new IllegalArgumentException("Via and preferred transports must match");
        }
        if (useRPort && preferredTransport != TransportProtocol.UDP) {
            throw new IllegalArgumentException("rport is only supported for UDP Dialog requests");
        }
    }

    /**
     * Creates a UDP request profile with rport enabled.
     *
     * @param sentBy local UDP sent-by value
     * @return UDP Dialog request profile
     */
    public static DialogRequestProfile udp(SentBy sentBy) {
        return new DialogRequestProfile(ViaTransport.UDP, sentBy, TransportProtocol.UDP, true);
    }
}
