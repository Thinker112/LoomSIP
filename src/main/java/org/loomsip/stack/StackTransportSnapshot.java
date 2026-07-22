package org.loomsip.stack;

import org.loomsip.transport.TransportDiagnosticsSnapshot;
import org.loomsip.transport.TransportEndpoint;
import org.loomsip.transport.TransportProtocol;
import org.loomsip.transport.TransportState;

import java.util.Optional;

/** Immutable lifecycle, binding, and counter snapshot for one configured Transport. */
public record StackTransportSnapshot(TransportProtocol protocol, TransportState state,
                                     Optional<TransportEndpoint> localEndpoint,
                                     TransportDiagnosticsSnapshot diagnostics) { }
