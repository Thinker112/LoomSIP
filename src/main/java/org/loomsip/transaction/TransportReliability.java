package org.loomsip.transaction;

import org.loomsip.transport.TransportProtocol;

import java.util.Objects;

/**
 * Reliability property used to enable or suppress transaction retransmission timers.
 */
public enum TransportReliability {
    /** Message delivery may be lost, as with UDP. */
    UNRELIABLE,
    /** An established transport reports delivery failure, as with TCP or TLS. */
    RELIABLE;

    /**
     * Derives reliability from a transport protocol.
     *
     * @param protocol transport protocol
     * @return UDP as unreliable; TCP and TLS as reliable
     */
    public static TransportReliability from(TransportProtocol protocol) {
        Objects.requireNonNull(protocol, "protocol");
        return protocol == TransportProtocol.UDP ? UNRELIABLE : RELIABLE;
    }
}
