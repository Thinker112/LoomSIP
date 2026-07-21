package org.loomsip.stack;

import org.loomsip.transport.TransportRegistry;

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
        this.transportRegistry = Objects.requireNonNull(transportRegistry, "transportRegistry");
        return this;
    }

    /**
     * Creates one Stack lifecycle runtime.
     *
     * @return unstarted Stack instance
     * @throws IllegalStateException if this builder was already consumed
     */
    public LoomSipStack build() {
        ensureMutable();
        built = true;
        return new DefaultLoomSipStack(
                config,
                resources == null ? StackResources.createDefault() : resources,
                transportRegistry == null ? new TransportRegistry() : transportRegistry
        );
    }

    private void ensureMutable() {
        if (built) {
            throw new IllegalStateException("LoomSipStackBuilder is already consumed");
        }
    }
}
