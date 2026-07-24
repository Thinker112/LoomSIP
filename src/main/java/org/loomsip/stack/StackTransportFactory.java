package org.loomsip.stack;

import org.loomsip.transport.SipMessageHandler;
import org.loomsip.transport.SipTransport;
import org.loomsip.transport.TransportProtocol;

import java.net.InetSocketAddress;
import java.util.Optional;

/**
 * Creates one Stack-owned Transport after its inbound dispatch target exists.
 *
 * <pre>{@code
 * StackTransportFactory --> SipMessageHandler --> SipTransport
 * }</pre>
 *
 * <p>Factories configure I/O only. They do not start a Transport, mutate
 * Transaction/Dialog state, or own the supplied inbound handler.</p>
 */
@FunctionalInterface
public interface StackTransportFactory {

    /**
     * Creates an unstarted Transport bound to the Stack inbound handler.
     *
     * @param inboundHandler Stack-owned inbound message and error callback
     * @return unstarted Transport; ownership transfers to the Stack
     */
    SipTransport create(SipMessageHandler inboundHandler);

    /** @return declared protocol when the Factory exposes static transport metadata */
    default Optional<TransportProtocol> protocol() { return Optional.empty(); }

    /** @return declared bind address when the Factory exposes static transport metadata */
    default Optional<InetSocketAddress> bindAddress() { return Optional.empty(); }
}
