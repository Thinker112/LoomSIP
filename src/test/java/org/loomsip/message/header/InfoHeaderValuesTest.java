package org.loomsip.message.header;

import org.junit.jupiter.api.Test;
import org.loomsip.message.SipHeaders;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class InfoHeaderValuesTest {

    @Test
    void parsesInfoPackageAndOrderedRecvInfoCapabilities() throws Exception {
        SipHeaders headers = SipHeaders.builder()
                .add("info-package", "conference")
                .add("Recv-Info", "conference, x-vendor")
                .add("recv-info", "dtmf")
                .build();

        InfoPackageHeaderValue infoPackage = SipHeaderValues.infoPackage(headers);
        RecvInfoHeaderValue recvInfo = SipHeaderValues.recvInfo(headers);

        assertEquals("conference", infoPackage.wireValue());
        assertEquals("conference", infoPackage.normalizedName());
        assertEquals(List.of("conference", "x-vendor", "dtmf"),
                recvInfo.packages().stream().map(InfoPackageHeaderValue::name).toList());
        assertEquals("conference, x-vendor, dtmf", recvInfo.wireValue());
        assertEquals("conference, x-vendor, dtmf", recvInfo.applyTo(SipHeaders.empty())
                .firstValue("Recv-Info").orElseThrow());
    }

    @Test
    void rejectsMissingMalformedAndDuplicateInfoCapabilities() {
        assertThrows(SipHeaderValueException.class,
                () -> SipHeaderValues.infoPackage(SipHeaders.empty()));
        assertThrows(SipHeaderValueException.class,
                () -> SipHeaderValues.infoPackage(SipHeaders.builder().add("Info-Package", "bad package").build()));
        assertThrows(SipHeaderValueException.class,
                () -> SipHeaderValues.recvInfo(SipHeaders.builder().add("Recv-Info", "conference,").build()));
        assertThrows(SipHeaderValueException.class,
                () -> SipHeaderValues.recvInfo(SipHeaders.builder()
                        .add("Recv-Info", "conference")
                        .add("Recv-Info", "CONFERENCE")
                        .build()));
    }
}
