package org.loomsip.transport;

/** Immutable connection and local-write counters for one Transport. */
public record TransportDiagnosticsSnapshot(int activeConnections, int pendingSends) {
    /** Validates non-negative counters. */
    public TransportDiagnosticsSnapshot {
        if (activeConnections < 0 || pendingSends < 0) throw new IllegalArgumentException("diagnostic counters must be non-negative");
    }
    /** Empty counters for Transports that do not expose diagnostics. */
    public static final TransportDiagnosticsSnapshot EMPTY = new TransportDiagnosticsSnapshot(0, 0);
}
