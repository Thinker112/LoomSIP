package org.loomsip.transport;

import java.net.InetSocketAddress;
import java.util.Objects;

/**
 * Network metadata associated with an inbound SIP message.
 *
 * @param protocol protocol on which the message arrived
 * @param localAddress local address that received the message
 * @param remoteAddress peer address that sent the message
 */
public record TransportContext(
        TransportProtocol protocol,
        InetSocketAddress localAddress,
        InetSocketAddress remoteAddress
) {

    /**
     * Validates and creates inbound transport metadata.
     *
     * @throws NullPointerException if a component is {@code null}
     */
    public TransportContext {
        Objects.requireNonNull(protocol, "protocol");
        Objects.requireNonNull(localAddress, "localAddress");
        Objects.requireNonNull(remoteAddress, "remoteAddress");
    }
}
