package org.loomsip.transport;

/** Optional non-protocol runtime diagnostics exposed by a Transport. */
public interface TransportDiagnostics {
    /** @return immutable current diagnostic counters */
    TransportDiagnosticsSnapshot diagnostics();
}
