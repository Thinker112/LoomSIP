package org.loomsip.info;

import org.loomsip.message.header.InfoPackageHeaderValue;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentSkipListMap;

/**
 * Thread-safe registry that selects one application handler for an INFO package.
 *
 * <pre>{@code
 * Info-Package token
 *        |
 *        v
 * Info Dispatcher -- lookup --> registered InfoHandler
 *        |                              |
 *        | no match                     v
 *        +--------> 469          asynchronous TU response
 * }</pre>
 *
 * <p>The registry does not own Dialog, Transaction, or executor state. A
 * lookup returns the handler selected at that instant, so a later unregister
 * operation cannot redirect an INFO request that has already started.</p>
 */
public final class InfoDispatcher {

    private final ConcurrentSkipListMap<String, Registration> handlers = new ConcurrentSkipListMap<>();

    /**
     * Registers one handler for an INFO package.
     *
     * @param infoPackage package to route
     * @param handler application callback
     * @throws IllegalStateException if the package already has a handler
     */
    public void register(InfoPackageHeaderValue infoPackage, InfoHandler handler) {
        InfoPackageHeaderValue selectedPackage = Objects.requireNonNull(infoPackage, "infoPackage");
        Registration registration = new Registration(selectedPackage, Objects.requireNonNull(handler, "handler"));
        Registration existing = handlers.putIfAbsent(selectedPackage.normalizedName(), registration);
        if (existing != null) {
            throw new IllegalStateException("INFO package is already registered: " + selectedPackage.name());
        }
    }

    /**
     * Removes the current handler for one INFO package.
     *
     * @param infoPackage package to remove
     * @return whether a registration was removed
     */
    public boolean unregister(InfoPackageHeaderValue infoPackage) {
        return handlers.remove(Objects.requireNonNull(infoPackage, "infoPackage").normalizedName()) != null;
    }

    /**
     * Finds the handler currently registered for a package.
     *
     * @param infoPackage package to route
     * @return selected handler, or empty when unsupported
     */
    public Optional<InfoHandler> find(InfoPackageHeaderValue infoPackage) {
        Registration registration = handlers.get(
                Objects.requireNonNull(infoPackage, "infoPackage").normalizedName()
        );
        return registration == null ? Optional.empty() : Optional.of(registration.handler());
    }

    /**
     * Returns currently registered packages in deterministic normalized-name order.
     *
     * @return immutable advertised package list
     */
    public List<InfoPackageHeaderValue> supportedPackages() {
        return handlers.values().stream().map(Registration::infoPackage).toList();
    }

    private record Registration(InfoPackageHeaderValue infoPackage, InfoHandler handler) {
    }
}
