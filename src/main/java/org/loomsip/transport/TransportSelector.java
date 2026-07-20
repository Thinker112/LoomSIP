package org.loomsip.transport;

import org.loomsip.message.SipMessage;

import java.util.Objects;
import java.util.concurrent.CompletionStage;

/**
 * Stateless protocol selector over a {@link TransportRegistry}.
 *
 * <pre>{@code
 * SipMessage + TransportEndpoint
 *              |
 *              v
 *       TransportSelector
 *              |
 *              v
 *       TransportRegistry
 *         |       |       |
 *        UDP     TCP     TLS
 * }</pre>
 *
 * <p>Selection uses only the already-resolved endpoint protocol. DNS NAPTR/SRV
 * and URI resolution remain outside this component.</p>
 */
public final class TransportSelector {

    private final TransportRegistry registry;

    /**
     * Creates a selector over an existing registry.
     *
     * @param registry transport registry used for routing
     */
    public TransportSelector(TransportRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    /**
     * Routes one message to UDP, TCP, or TLS according to its target.
     *
     * @param message immutable SIP message
     * @param target resolved transport target
     * @return asynchronous transport write result
     */
    public CompletionStage<SendResult> send(SipMessage message, TransportEndpoint target) {
        return registry.send(
                Objects.requireNonNull(message, "message"),
                Objects.requireNonNull(target, "target")
        );
    }

    /**
     * Returns the registry used by this selector.
     *
     * @return backing registry
     */
    public TransportRegistry registry() {
        return registry;
    }
}
