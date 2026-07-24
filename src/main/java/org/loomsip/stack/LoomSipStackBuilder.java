package org.loomsip.stack;

import org.loomsip.transport.TransportRegistry;
import org.loomsip.transport.TransportProtocol;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/**
 * Single-use builder for the Stack lifecycle skeleton.
 *
 * <pre>{@code
 * config + resources + registry --> LoomSipStackBuilder --> LoomSipStack
 * }</pre>
 *
 * <p>Passing a {@link TransportRegistry} transfers its lifecycle to the
 * resulting Stack. Transport factories and protocol component assembly are
 * intentionally deferred to later Stack phases.</p>
 */
public final class LoomSipStackBuilder {

    private SipStackConfig config = SipStackConfig.DEFAULT;
    private StackResources resources;
    private TransportRegistry transportRegistry;
    private TuHandlerRegistry handlers = TuHandlerRegistry.builder().build();
    private SipStackListener listener = new SipStackListener() { };
    private DialogStackConfig dialogConfig;
    private SipStackApplication application;
    private final EnumMap<TransportProtocol, StackTransportFactory> transportFactories =
            new EnumMap<>(TransportProtocol.class);
    private boolean built;

    /** Creates a builder with default lifecycle configuration. */
    public LoomSipStackBuilder() {
    }

    /**
     * Replaces the immutable lifecycle configuration.
     *
     * @param config Stack lifecycle settings
     * @return this builder
     * @throws IllegalStateException if build has already been called
     */
    public LoomSipStackBuilder config(SipStackConfig config) {
        ensureMutable();
        this.config = Objects.requireNonNull(config, "config");
        return this;
    }

    /**
     * Supplies executor and scheduler resources with ownership encoded by the resource object.
     *
     * @param resources callback and timer resources
     * @return this builder
     * @throws IllegalStateException if build has already been called
     */
    public LoomSipStackBuilder resources(StackResources resources) {
        ensureMutable();
        this.resources = Objects.requireNonNull(resources, "resources");
        return this;
    }

    /**
     * Transfers a prepared transport registry to the resulting Stack.
     *
     * <p>The registry must still be configurable. Stack start invokes its
     * start operation and Stack close invokes its close operation.</p>
     *
     * @param transportRegistry registry transferred to Stack lifecycle ownership
     * @return this builder
     * @throws IllegalStateException if build has already been called
     */
    public LoomSipStackBuilder transportRegistry(TransportRegistry transportRegistry) {
        ensureMutable();
        if (!transportFactories.isEmpty()) {
            throw new IllegalStateException("cannot combine a transport registry with transport factories");
        }
        this.transportRegistry = Objects.requireNonNull(transportRegistry, "transportRegistry");
        return this;
    }

    /**
     * Registers one Stack-owned Transport factory for a protocol.
     *
     * <p>Factory invocation is deferred to {@link LoomSipStack#start()}, so
     * {@link #build()} neither binds a port nor creates Netty resources.</p>
     *
     * @param protocol protocol supplied by the factory
     * @param factory factory that creates the matching unstarted Transport
     * @return this builder
     * @throws IllegalStateException if a Registry was supplied, a Factory for the
     *                               protocol already exists, or build has run
     */
    public LoomSipStackBuilder transport(TransportProtocol protocol, StackTransportFactory factory) {
        ensureMutable();
        if (transportRegistry != null) {
            throw new IllegalStateException("cannot combine transport factories with a transport registry");
        }
        Objects.requireNonNull(protocol, "protocol");
        Objects.requireNonNull(factory, "factory");
        if (transportFactories.putIfAbsent(protocol, factory) != null) {
            throw new IllegalArgumentException("transport factory already registered for " + protocol);
        }
        return this;
    }

    /**
     * Supplies the immutable inbound Transaction User routing table.
     *
     * @param handlers startup-time request handler registrations
     * @return this builder
     * @throws IllegalStateException if build has already been called
     */
    public LoomSipStackBuilder handlers(TuHandlerRegistry handlers) {
        ensureMutable();
        this.handlers = Objects.requireNonNull(handlers, "handlers");
        return this;
    }

    /** Supplies frozen capability-based application registrations. */
    public LoomSipStackBuilder application(SipStackApplication application) {
        ensureMutable();
        this.application = Objects.requireNonNull(application, "application");
        this.handlers = application.requests();
        return this;
    }

    /** @param listener isolated lifecycle and failure observer @return this builder */
    public LoomSipStackBuilder listener(SipStackListener listener) {
        ensureMutable(); this.listener = Objects.requireNonNull(listener, "listener"); return this;
    }

    /** Enables automatic Dialog Runtime and Transaction Bridge assembly. */
    public LoomSipStackBuilder dialog(DialogStackConfig dialogConfig) {
        ensureMutable(); this.dialogConfig = Objects.requireNonNull(dialogConfig, "dialogConfig"); return this;
    }

    /**
     * Creates one Stack lifecycle runtime.
     *
     * @return unstarted Stack instance
     * @throws IllegalStateException if this builder was already consumed
     */
    public LoomSipStack build() {
        ensureMutable();
        validateDialogTransport();
        built = true;
        StackResources actualResources = resources == null ? StackResources.createDefault() : resources;
        TransportRegistry actualRegistry = transportRegistry == null ? new TransportRegistry() : transportRegistry;
        StackTransactionRuntime transactionRuntime = new StackTransactionRuntime(
                actualRegistry, actualResources, application, handlers, dialogConfig
        );
        return new DefaultLoomSipStack(
                config,
                actualResources,
                new StackTransportAssembly(
                        actualRegistry, Map.copyOf(transportFactories), transactionRuntime.dispatcher()
                ),
                transactionRuntime, listener
        );
    }

    private void validateDialogTransport() {
        if (dialogConfig == null) return;
        StackTransportFactory factory = transportFactories.get(dialogConfig.requestProfile().preferredTransport());
        if (factory == null) throw new IllegalStateException("Dialog transport is not configured: " + dialogConfig.requestProfile().preferredTransport());
        factory.bindAddress().ifPresent(address -> {
            if (address.getPort() == 0) throw new IllegalArgumentException("Stack Dialog transport bind port must not be zero");
            if (address.getPort() != dialogConfig.requestProfile().sentBy().port()) throw new IllegalArgumentException("Dialog sent-by port must match transport bind port");
        });
    }

    private void ensureMutable() {
        if (built) {
            throw new IllegalStateException("LoomSipStackBuilder is already consumed");
        }
    }
}
