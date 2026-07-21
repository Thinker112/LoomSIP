package org.loomsip.testkit;

import org.loomsip.codec.SipParseException;
import org.loomsip.transport.InboundSipMessage;
import org.loomsip.transport.SipMessageHandler;
import org.loomsip.transport.TransportContext;

import java.util.Objects;

/**
 * Delays transport callback binding until a scenario endpoint is fully assembled.
 *
 * <pre>{@code
 * SipTransport --> ScenarioInboundHandler --> ScenarioEndpoint Dispatcher
 * }</pre>
 */
public final class ScenarioInboundHandler implements SipMessageHandler {

    private volatile SipMessageHandler delegate;

    /**
     * Binds the fully assembled inbound dispatcher.
     *
     * @param delegate transaction dispatcher receiving transport callbacks
     */
    public void bind(SipMessageHandler delegate) {
        if (this.delegate != null) {
            throw new IllegalStateException("scenario inbound handler is already bound");
        }
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    @Override
    public void onMessage(InboundSipMessage message) {
        currentDelegate().onMessage(message);
    }

    @Override
    public void onMalformedMessage(TransportContext context, SipParseException cause) {
        currentDelegate().onMalformedMessage(context, cause);
    }

    @Override
    public void onTransportError(Throwable cause) {
        currentDelegate().onTransportError(cause);
    }

    private SipMessageHandler currentDelegate() {
        SipMessageHandler selected = delegate;
        if (selected == null) {
            throw new IllegalStateException("scenario inbound handler is not bound");
        }
        return selected;
    }
}
