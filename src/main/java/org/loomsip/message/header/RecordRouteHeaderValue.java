package org.loomsip.message.header;

import org.loomsip.message.SipUri;

import java.util.Objects;

/**
 * Typed Record-Route value used to establish a Dialog Route Set.
 *
 * @param address recorded name-addr and external parameters
 */
public record RecordRouteHeaderValue(AddressHeaderValue address) {

    /** Validates the recorded route address. */
    public RecordRouteHeaderValue {
        Objects.requireNonNull(address, "address");
    }

    /**
     * Returns the URI carried by this Record-Route value.
     *
     * @return recorded route URI
     */
    public SipUri uri() {
        return address.uri();
    }

    /**
     * Tests whether the recorded route requests loose routing.
     *
     * @return whether the URI contains the {@code lr} parameter
     */
    public boolean looseRouting() {
        return address.hasUriParameter("lr");
    }

    /**
     * Renders the Record-Route value for a SIP header field.
     *
     * @return normalized wire value
     */
    public String wireValue() {
        return address.wireValue();
    }
}
