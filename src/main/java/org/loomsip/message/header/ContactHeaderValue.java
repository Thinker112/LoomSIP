package org.loomsip.message.header;

import java.util.Objects;
import java.util.Optional;

/**
 * Typed Contact value, including the special wildcard form.
 *
 * @param address contact address, or empty for {@code *}
 */
public record ContactHeaderValue(Optional<AddressHeaderValue> address) {

    /** Validates the optional contact address. */
    public ContactHeaderValue {
        Objects.requireNonNull(address, "address");
    }

    /**
     * Creates the special wildcard Contact value.
     *
     * @return wildcard Contact value
     */
    public static ContactHeaderValue wildcard() {
        return new ContactHeaderValue(Optional.empty());
    }

    /**
     * Reports whether this is the special wildcard Contact.
     *
     * @return whether this value is {@code *}
     */
    public boolean isWildcard() {
        return address.isEmpty();
    }

    /**
     * Renders the Contact value for a SIP header field.
     *
     * @return normalized wire value
     */
    public String wireValue() {
        return address.map(AddressHeaderValue::wireValue).orElse("*");
    }
}
