package org.loomsip.transport;

/**
 * Network transport protocols supported by SIP endpoints.
 */
public enum TransportProtocol {
    /** User Datagram Protocol. */
    UDP,
    /** Transmission Control Protocol. */
    TCP,
    /** SIP over Transport Layer Security. */
    TLS
}
