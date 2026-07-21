package org.loomsip.message.header;

import java.util.Objects;

/**
 * Typed RFC 3515 Refer-To target address.
 *
 * <pre>{@code
 * Refer-To header --> AddressHeaderValue --> referral target URI
 * }</pre>
 *
 * @param address target name-addr or addr-spec, including any URI parameters
 */
public record ReferToHeaderValue(AddressHeaderValue address) {

    /** Validates the required referral target address. */
    public ReferToHeaderValue {
        Objects.requireNonNull(address, "address");
    }

    /**
     * Parses one Refer-To field value.
     *
     * @param value raw Refer-To field value
     * @return structured referral target
     * @throws SipHeaderValueException if the value is not a valid address
     */
    public static ReferToHeaderValue parse(String value) throws SipHeaderValueException {
        return new ReferToHeaderValue(AddressHeaderParser.parseAddress(value, "Refer-To"));
    }

    /**
     * Renders this target as a normalized Refer-To field value.
     *
     * @return normalized header value
     */
    public String wireValue() {
        return address.wireValue();
    }
}
