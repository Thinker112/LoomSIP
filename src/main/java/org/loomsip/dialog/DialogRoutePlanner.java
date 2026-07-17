package org.loomsip.dialog;

import org.loomsip.message.SipUri;
import org.loomsip.message.header.RecordRouteHeaderValue;
import org.loomsip.message.header.RouteHeaderValue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Applies SIP loose-routing and strict-routing rules without performing DNS.
 *
 * <pre>{@code
 * Dialog Snapshot
 *       |
 *       v
 * DialogRoutePlanner
 *       |
 *       +--> Request-URI
 *       +--> ordered Route headers
 *       +--> Next-Hop URI
 *                    |
 *                    v
 *          DialogTargetResolver
 * }</pre>
 */
public final class DialogRoutePlanner {

    /** Creates a stateless Dialog route planner. */
    public DialogRoutePlanner() {
    }

    /**
     * Creates a request routing plan from the latest immutable Dialog state.
     *
     * @param dialog Dialog state containing the Route Set and Remote Target
     * @return immutable routing plan
     * @throws IllegalStateException if the Dialog has no Remote Target
     */
    public DialogRoutePlan plan(DialogSnapshot dialog) {
        Objects.requireNonNull(dialog, "dialog");
        SipUri remoteTarget = dialog.remoteTarget().orElseThrow(
                () -> new IllegalStateException("Dialog has no Remote Target: " + dialog.id())
        );
        List<RouteHeaderValue> routeSet = dialog.routeSet();
        if (routeSet.isEmpty()) {
            return new DialogRoutePlan(remoteTarget, List.of(), remoteTarget);
        }

        RouteHeaderValue firstRoute = routeSet.getFirst();
        if (firstRoute.looseRouting()) {
            return new DialogRoutePlan(remoteTarget, routeSet, firstRoute.uri());
        }

        List<RouteHeaderValue> strictRoutes = new ArrayList<>(routeSet.size());
        strictRoutes.addAll(routeSet.subList(1, routeSet.size()));
        strictRoutes.add(RouteHeaderValue.of(remoteTarget));
        return new DialogRoutePlan(firstRoute.uri(), strictRoutes, firstRoute.uri());
    }

    /**
     * Establishes the immutable Route Set from Record-Route wire values.
     *
     * <p>A UAC reverses response Record-Route order. A UAS preserves request
     * Record-Route order.</p>
     *
     * @param role local endpoint role
     * @param recordRoutes Record-Route values in wire order
     * @return Route values in local request order
     */
    public List<RouteHeaderValue> establishRouteSet(
            DialogRole role,
            List<RecordRouteHeaderValue> recordRoutes
    ) {
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(recordRoutes, "recordRoutes");
        List<RecordRouteHeaderValue> ordered = new ArrayList<>(recordRoutes);
        if (role == DialogRole.UAC) {
            Collections.reverse(ordered);
        }
        return ordered.stream().map(value -> new RouteHeaderValue(value.address())).toList();
    }
}
