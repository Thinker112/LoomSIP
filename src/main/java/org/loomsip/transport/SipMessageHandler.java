package org.loomsip.transport;

import org.loomsip.codec.SipParseException;

/**
 * Receives decoded messages and transport diagnostics outside the Netty EventLoop.
 *
 * <p>Callbacks may run concurrently for different datagrams or stream
 * connections. Transaction and Dialog ordering is provided by their dispatcher
 * and mailbox layers rather than by the transport callback executor.</p>
 */
public interface SipMessageHandler {

    /**
     * Handles one successfully parsed inbound message.
     *
     * @param message immutable message and network context
     */
    void onMessage(InboundSipMessage message);

    /**
     * Reports a datagram or stream connection that could not be decoded as SIP.
     *
     * @param context source and destination metadata
     * @param cause parse or configured-size failure
     */
    default void onMalformedMessage(TransportContext context, SipParseException cause) {
    }

    /**
     * Reports a channel, callback, or dispatch failure.
     *
     * @param cause failure that did not produce a valid inbound message
     */
    default void onTransportError(Throwable cause) {
    }
}
