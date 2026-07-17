package org.loomsip.message.header;

import org.loomsip.message.SipHeader;
import org.loomsip.message.SipHeaders;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Parses typed Contact, Route, and Record-Route values for Dialog processing.
 *
 * <pre>{@code
 * SipHeaders
 *     |
 *     v
 * DialogHeaderValues
 *     |
 *     +--> ContactHeaderValue
 *     +--> RouteHeaderValue
 *     +--> RecordRouteHeaderValue
 * }</pre>
 */
public final class DialogHeaderValues {

    private DialogHeaderValues() {
    }

    /**
     * Parses the single From address.
     *
     * @param headers message headers
     * @return typed From address
     * @throws SipHeaderValueException if the field is absent, duplicated, or malformed
     */
    public static AddressHeaderValue fromAddress(SipHeaders headers) throws SipHeaderValueException {
        return singleAddress(headers, "From");
    }

    /**
     * Parses the single To address.
     *
     * @param headers message headers
     * @return typed To address
     * @throws SipHeaderValueException if the field is absent, duplicated, or malformed
     */
    public static AddressHeaderValue toAddress(SipHeaders headers) throws SipHeaderValueException {
        return singleAddress(headers, "To");
    }

    /**
     * Parses every Contact list entry in wire order.
     *
     * @param headers message headers
     * @return immutable Contact values
     * @throws SipHeaderValueException if a value is malformed
     */
    public static List<ContactHeaderValue> contacts(SipHeaders headers) throws SipHeaderValueException {
        Objects.requireNonNull(headers, "headers");
        List<ContactHeaderValue> values = new ArrayList<>();
        for (SipHeader header : headers.all("Contact")) {
            for (String raw : AddressHeaderParser.splitList(header.value(), "Contact")) {
                values.add("*".equals(raw)
                        ? ContactHeaderValue.wildcard()
                        : new ContactHeaderValue(java.util.Optional.of(
                                AddressHeaderParser.parseAddress(raw, "Contact")
                        )));
            }
        }
        return List.copyOf(values);
    }

    /**
     * Parses every Route list entry in wire order.
     *
     * @param headers message headers
     * @return immutable Route values
     * @throws SipHeaderValueException if a value is malformed
     */
    public static List<RouteHeaderValue> routes(SipHeaders headers) throws SipHeaderValueException {
        Objects.requireNonNull(headers, "headers");
        List<RouteHeaderValue> values = new ArrayList<>();
        for (SipHeader header : headers.all("Route")) {
            for (String raw : AddressHeaderParser.splitList(header.value(), "Route")) {
                values.add(new RouteHeaderValue(AddressHeaderParser.parseAddress(raw, "Route")));
            }
        }
        return List.copyOf(values);
    }

    /**
     * Parses every Record-Route list entry in wire order.
     *
     * @param headers message headers
     * @return immutable Record-Route values
     * @throws SipHeaderValueException if a value is malformed
     */
    public static List<RecordRouteHeaderValue> recordRoutes(SipHeaders headers)
            throws SipHeaderValueException {
        Objects.requireNonNull(headers, "headers");
        List<RecordRouteHeaderValue> values = new ArrayList<>();
        for (SipHeader header : headers.all("Record-Route")) {
            for (String raw : AddressHeaderParser.splitList(header.value(), "Record-Route")) {
                values.add(new RecordRouteHeaderValue(
                        AddressHeaderParser.parseAddress(raw, "Record-Route")
                ));
            }
        }
        return List.copyOf(values);
    }

    private static AddressHeaderValue singleAddress(SipHeaders headers, String name)
            throws SipHeaderValueException {
        Objects.requireNonNull(headers, "headers");
        List<SipHeader> fields = headers.all(name);
        if (fields.size() != 1) {
            throw new SipHeaderValueException(
                    name + " must appear exactly once but appeared " + fields.size() + " times"
            );
        }
        List<String> values = AddressHeaderParser.splitList(fields.getFirst().value(), name);
        if (values.size() != 1) {
            throw new SipHeaderValueException(name + " must contain exactly one address");
        }
        return AddressHeaderParser.parseAddress(values.getFirst(), name);
    }
}
