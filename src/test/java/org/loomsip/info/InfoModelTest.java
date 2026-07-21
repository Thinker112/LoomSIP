package org.loomsip.info;

import org.junit.jupiter.api.Test;
import org.loomsip.message.SipBody;
import org.loomsip.message.SipHeaders;
import org.loomsip.message.header.InfoPackageHeaderValue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class InfoModelTest {

    @Test
    void modelsPackagedRequestAndSuccessfulResponse() {
        InfoRequest request = new InfoRequest(
                new InfoPackageHeaderValue("conference"),
                SipHeaders.builder().add("Content-Type", "application/conference-info+xml").build(),
                SipBody.empty()
        );

        assertEquals("conference", request.infoPackage().name());
        assertEquals(200, InfoResponse.ok().statusCode());
    }

    @Test
    void rejectsNonFinalAndTransactionManagedResponseHeaders() {
        assertThrows(IllegalArgumentException.class, () -> new InfoResponse(
                180,
                "Ringing",
                SipHeaders.empty(),
                SipBody.empty()
        ));
        assertThrows(IllegalArgumentException.class, () -> new InfoResponse(
                200,
                "OK",
                SipHeaders.builder().add("CSeq", "2 INFO").build(),
                SipBody.empty()
        ));
    }
}
