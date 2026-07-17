package org.loomsip.dialog;

import org.loomsip.message.SipUri;
import org.loomsip.transport.TransportEndpoint;
import org.loomsip.transport.TransportProtocol;

import java.util.concurrent.CompletionStage;

/**
 * Asynchronous boundary between SIP route planning and network target selection.
 *
 * <p>Implementations may initially support explicit IP addresses and ports.
 * RFC 3263 NAPTR and SRV processing can be added behind this interface without
 * coupling DNS work to a Dialog Mailbox.</p>
 */
@FunctionalInterface
public interface DialogTargetResolver {

    /**
     * Resolves the next-hop URI to one concrete transport endpoint.
     *
     * @param nextHop URI produced by {@link DialogRoutePlanner}
     * @param preferredTransport preferred transport for this request
     * @return asynchronous resolved endpoint
     */
    CompletionStage<TransportEndpoint> resolve(
            SipUri nextHop,
            TransportProtocol preferredTransport
    );
}
