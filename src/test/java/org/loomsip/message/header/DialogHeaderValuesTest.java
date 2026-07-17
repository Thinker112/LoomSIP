package org.loomsip.message.header;

import org.junit.jupiter.api.Test;
import org.loomsip.message.SipHeaders;
import org.loomsip.message.SipUri;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DialogHeaderValuesTest {

    @Test
    void parsesQuotedCommaUriParametersAndExternalParameters() throws Exception {
        SipHeaders headers = SipHeaders.builder()
                .add("Contact", "\"Doe, Alice\" <sip:alice@client.example.com;transport=udp>"
                        + ";expires=120;note=\"desk, one\"")
                .build();

        ContactHeaderValue contact = DialogHeaderValues.contacts(headers).getFirst();
        AddressHeaderValue address = contact.address().orElseThrow();

        assertEquals("Doe, Alice", address.displayName().orElseThrow());
        assertEquals(SipUri.parse("sip:alice@client.example.com;transport=udp"), address.uri());
        assertEquals("120", address.parameter("expires").orElseThrow().value().orElseThrow());
        assertEquals("desk, one", address.parameter("note").orElseThrow().value().orElseThrow());
        assertFalse(contact.isWildcard());
    }

    @Test
    void preservesRepeatedAndCommaSeparatedRouteOrder() throws Exception {
        SipHeaders headers = SipHeaders.builder()
                .add("Route", "<sip:edge-1.example.com;lr>, <sip:edge-2.example.com>")
                .add("Route", "<sip:edge-3.example.com;lr>;extension=value")
                .add("Record-Route", "<sip:proxy-1.example.com;lr>, <sip:proxy-2.example.com>")
                .add("Record-Route", "<sip:proxy-3.example.com;lr>")
                .build();

        List<RouteHeaderValue> routes = DialogHeaderValues.routes(headers);
        List<RecordRouteHeaderValue> recordRoutes = DialogHeaderValues.recordRoutes(headers);

        assertEquals(List.of(
                "sip:edge-1.example.com;lr",
                "sip:edge-2.example.com",
                "sip:edge-3.example.com;lr"
        ), routes.stream().map(value -> value.uri().value()).toList());
        assertTrue(routes.getFirst().looseRouting());
        assertFalse(routes.get(1).looseRouting());
        assertEquals("value", routes.getLast().address()
                .parameter("extension").orElseThrow().value().orElseThrow());
        assertEquals(List.of(
                "sip:proxy-1.example.com;lr",
                "sip:proxy-2.example.com",
                "sip:proxy-3.example.com;lr"
        ), recordRoutes.stream().map(value -> value.uri().value()).toList());
    }

    @Test
    void parsesWildcardContact() throws Exception {
        ContactHeaderValue contact = DialogHeaderValues.contacts(
                SipHeaders.builder().add("Contact", "*").build()
        ).getFirst();

        assertTrue(contact.isWildcard());
        assertEquals("*", contact.wireValue());
    }

    @Test
    void parsesSingleFromAndToAddresses() throws Exception {
        SipHeaders headers = SipHeaders.builder()
                .add("From", "\"Alice\" <sip:alice@example.com>;tag=from-tag")
                .add("To", "<sips:bob@example.com>;tag=to-tag")
                .build();

        assertEquals(SipUri.parse("sip:alice@example.com"),
                DialogHeaderValues.fromAddress(headers).uri());
        assertEquals(SipUri.parse("sips:bob@example.com"),
                DialogHeaderValues.toAddress(headers).uri());
        assertEquals("from-tag", DialogHeaderValues.fromAddress(headers)
                .parameter("tag").orElseThrow().value().orElseThrow());
    }

    @Test
    void rejectsMalformedQuotesAnglesAndEmptyListValues() {
        assertThrows(SipHeaderValueException.class, () -> DialogHeaderValues.contacts(
                SipHeaders.builder().add("Contact", "\"Alice <sip:alice@example.com>").build()
        ));
        assertThrows(SipHeaderValueException.class, () -> DialogHeaderValues.routes(
                SipHeaders.builder().add("Route", "<sip:one.example.com;lr").build()
        ));
        assertThrows(SipHeaderValueException.class, () -> DialogHeaderValues.recordRoutes(
                SipHeaders.builder().add("Record-Route", "<sip:one.example.com;lr>,").build()
        ));
        assertThrows(SipHeaderValueException.class, () -> DialogHeaderValues.fromAddress(
                SipHeaders.builder()
                        .add("From", "<sip:one@example.com>")
                        .add("From", "<sip:two@example.com>")
                        .build()
        ));
    }
}
