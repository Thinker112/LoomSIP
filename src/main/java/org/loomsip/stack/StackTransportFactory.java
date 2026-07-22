package org.loomsip.stack;

import org.loomsip.transport.SipMessageHandler;
import org.loomsip.transport.SipTransport;

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
}
