package org.loomsip.dialog;

import org.loomsip.message.SipUri;
import org.loomsip.message.header.RouteHeaderValue;

import java.util.List;
import java.util.Objects;

/**
 * Immutable routing result used to construct and transmit an in-Dialog request.
 *
 * @param requestUri URI written to the request line
 * @param routes Route header values written in order
 * @param nextHop URI passed to the target resolver
 */
public record DialogRoutePlan(
        SipUri requestUri,
        List<RouteHeaderValue> routes,
        SipUri nextHop
) {

    /** Validates and defensively copies the routing result. */
    public DialogRoutePlan {
        Objects.requireNonNull(requestUri, "requestUri");
        routes = List.copyOf(Objects.requireNonNull(routes, "routes"));
        Objects.requireNonNull(nextHop, "nextHop");
    }
}
