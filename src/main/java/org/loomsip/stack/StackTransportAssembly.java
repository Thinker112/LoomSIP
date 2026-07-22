package org.loomsip.stack;

import org.loomsip.transport.SipMessageHandler;
import org.loomsip.transport.SipTransport;
import org.loomsip.transport.TransportProtocol;
import org.loomsip.transport.TransportRegistry;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/**
 * Defers Factory invocation until Stack startup, then delegates lifecycle to a Registry.
 *
 * <pre>{@code
 * factories --> create unstarted transports --> TransportRegistry.start()
 *                         failure ---------> TransportRegistry.close()
 * }</pre>
 */
final class StackTransportAssembly {

    private final TransportRegistry registry;
    private final Map<TransportProtocol, StackTransportFactory> factories;
    private final SipMessageHandler inboundHandler;
    private boolean created;

    StackTransportAssembly(
            TransportRegistry registry,
            Map<TransportProtocol, StackTransportFactory> factories,
            SipMessageHandler inboundHandler
    ) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.factories = new EnumMap<>(TransportProtocol.class);
        this.factories.putAll(Objects.requireNonNull(factories, "factories"));
        this.inboundHandler = Objects.requireNonNull(inboundHandler, "inboundHandler");
    }

    synchronized void start() throws Exception {
        if (!created) {
            try {
                for (Map.Entry<TransportProtocol, StackTransportFactory> entry : factories.entrySet()) {
                    SipTransport transport = Objects.requireNonNull(
                            entry.getValue().create(inboundHandler),
                            "transport factory result"
                    );
                    registry.register(entry.getKey(), transport);
                }
                created = true;
            } catch (Throwable failure) {
                registry.close();
                throw failure;
            }
        }
        registry.start();
    }

    void close() {
        registry.close();
    }

    java.util.List<StackTransportSnapshot> snapshot() {
        return registry.transports().entrySet().stream().map(entry -> {
            var transport = entry.getValue();
            java.util.Optional<org.loomsip.transport.TransportEndpoint> endpoint;
            try { endpoint = java.util.Optional.of(transport.localEndpoint()); }
            catch (IllegalStateException ignored) { endpoint = java.util.Optional.empty(); }
            var diagnostics = transport instanceof org.loomsip.transport.TransportDiagnostics value
                    ? value.diagnostics() : org.loomsip.transport.TransportDiagnosticsSnapshot.EMPTY;
            return new StackTransportSnapshot(entry.getKey(), transport.state(), endpoint, diagnostics);
        }).toList();
    }
}
