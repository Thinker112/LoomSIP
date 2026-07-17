package org.loomsip.message.header;

import org.loomsip.message.SipUri;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Typed Route value used by Dialog route planning.
 *
 * @param address route name-addr and external parameters
 */
public record RouteHeaderValue(AddressHeaderValue address) {

    /** Validates the route address. */
    public RouteHeaderValue {
        Objects.requireNonNull(address, "address");
    }

    /**
     * Creates a Route value containing only the supplied URI.
     *
     * @param uri route URI
     * @return normalized Route value
     */
    public static RouteHeaderValue of(SipUri uri) {
        return new RouteHeaderValue(new AddressHeaderValue(
                Optional.empty(),
                Objects.requireNonNull(uri, "uri"),
                List.of()
        ));
    }

    /**
     * Returns the URI carried by this Route value.
     *
     * @return route URI
     */
    public SipUri uri() {
        return address.uri();
    }

    /**
     * Tests whether the route requests loose routing.
     *
     * @return whether the URI contains the {@code lr} parameter
     */
    public boolean looseRouting() {
        return address.hasUriParameter("lr");
    }

    /**
     * Renders the Route value for a SIP header field.
     *
     * @return normalized wire value
     */
    public String wireValue() {
        return address.wireValue();
    }
}
