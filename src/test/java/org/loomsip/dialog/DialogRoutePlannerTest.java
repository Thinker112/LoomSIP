package org.loomsip.dialog;

import org.junit.jupiter.api.Test;
import org.loomsip.message.SipHeaders;
import org.loomsip.message.SipUri;
import org.loomsip.message.header.DialogHeaderValues;
import org.loomsip.message.header.RecordRouteHeaderValue;
import org.loomsip.message.header.RouteHeaderValue;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DialogRoutePlannerTest {

    private final DialogRoutePlanner planner = new DialogRoutePlanner();

    @Test
    void targetsRemoteContactWhenRouteSetIsEmpty() {
        DialogRoutePlan plan = planner.plan(snapshot(List.of(), Optional.of(uri("sip:bob@target.example.com"))));

        assertEquals(uri("sip:bob@target.example.com"), plan.requestUri());
        assertEquals(List.of(), plan.routes());
        assertEquals(uri("sip:bob@target.example.com"), plan.nextHop());
    }

    @Test
    void appliesLooseRoutingWithoutRewritingRouteSet() {
        List<RouteHeaderValue> routes = List.of(
                route("sip:edge-1.example.com;lr"),
                route("sip:edge-2.example.com;lr")
        );

        DialogRoutePlan plan = planner.plan(snapshot(
                routes,
                Optional.of(uri("sip:bob@target.example.com"))
        ));

        assertEquals(uri("sip:bob@target.example.com"), plan.requestUri());
        assertEquals(routes, plan.routes());
        assertEquals(uri("sip:edge-1.example.com;lr"), plan.nextHop());
        assertThrows(UnsupportedOperationException.class, () -> plan.routes().add(route("sip:x.example.com")));
    }

    @Test
    void appliesStrictRoutingAndAppendsRemoteTarget() {
        DialogRoutePlan plan = planner.plan(snapshot(
                List.of(
                        route("sip:strict.example.com"),
                        route("sip:loose.example.com;lr")
                ),
                Optional.of(uri("sip:bob@target.example.com"))
        ));

        assertEquals(uri("sip:strict.example.com"), plan.requestUri());
        assertEquals(List.of(
                "sip:loose.example.com;lr",
                "sip:bob@target.example.com"
        ), plan.routes().stream().map(value -> value.uri().value()).toList());
        assertEquals(uri("sip:strict.example.com"), plan.nextHop());
    }

    @Test
    void establishesRoleSpecificRouteSetOrder() throws Exception {
        SipHeaders headers = SipHeaders.builder()
                .add("Record-Route", "<sip:first.example.com;lr>, <sip:second.example.com;lr>")
                .build();
        List<RecordRouteHeaderValue> wireOrder = DialogHeaderValues.recordRoutes(headers);

        assertEquals(List.of("sip:second.example.com;lr", "sip:first.example.com;lr"),
                planner.establishRouteSet(DialogRole.UAC, wireOrder).stream()
                        .map(value -> value.uri().value()).toList());
        assertEquals(List.of("sip:first.example.com;lr", "sip:second.example.com;lr"),
                planner.establishRouteSet(DialogRole.UAS, wireOrder).stream()
                        .map(value -> value.uri().value()).toList());
    }

    @Test
    void refusesToRouteAnEarlyDialogWithoutRemoteTarget() {
        assertThrows(IllegalStateException.class, () -> planner.plan(snapshot(List.of(), Optional.empty())));
    }

    private static DialogSnapshot snapshot(
            List<RouteHeaderValue> routes,
            Optional<SipUri> remoteTarget
    ) {
        return new DialogSnapshot(
                new DialogId("call-1@example.com", "local", "remote"),
                DialogRole.UAC,
                DialogState.EARLY,
                uri("sip:alice@example.com"),
                uri("sip:bob@example.com"),
                1,
                1,
                routes,
                remoteTarget,
                false
        );
    }

    private static RouteHeaderValue route(String value) {
        return RouteHeaderValue.of(uri(value));
    }

    private static SipUri uri(String value) {
        return SipUri.parse(value);
    }
}
