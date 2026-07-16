package org.loomsip.transport;

import org.loomsip.codec.SipParseException;

/**
 * Receives decoded messages and transport diagnostics outside the Netty EventLoop.
 *
 * <p>Callbacks may run concurrently for different datagrams. Transaction-level
 * ordering will be added by the dispatcher and mailbox layer in a later milestone.</p>
 */
public interface SipMessageHandler {

    /**
     * Handles one successfully parsed inbound message.
     *
     * @param message immutable message and network context
     */
    void onMessage(InboundSipMessage message);

    /**
     * Reports a datagram that could not be accepted as a SIP message.
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
