package org.loomsip.info;

import org.junit.jupiter.api.Test;
import org.loomsip.message.SipBody;
import org.loomsip.message.SipHeaders;
import org.loomsip.message.header.InfoPackageHeaderValue;

import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InfoDispatcherTest {

    @Test
    void findsCaseInsensitiveRegistrationAndAdvertisesDeterministically() {
        InfoDispatcher dispatcher = new InfoDispatcher();
        InfoHandler handler = request -> CompletableFuture.completedFuture(InfoResponse.ok());

        dispatcher.register(new InfoPackageHeaderValue("zeta"), handler);
        dispatcher.register(new InfoPackageHeaderValue("Conference"), handler);

        assertTrue(dispatcher.find(new InfoPackageHeaderValue("conference")).isPresent());
        assertEquals("Conference", dispatcher.supportedPackages().getFirst().name());
        assertEquals("zeta", dispatcher.supportedPackages().get(1).name());
        assertThrows(IllegalStateException.class,
                () -> dispatcher.register(new InfoPackageHeaderValue("CONFERENCE"), handler));
    }

    @Test
    void unregisterRemovesOnlyFutureLookups() {
        InfoDispatcher dispatcher = new InfoDispatcher();
        InfoPackageHeaderValue infoPackage = new InfoPackageHeaderValue("conference");
        dispatcher.register(infoPackage, request -> CompletableFuture.completedFuture(InfoResponse.ok()));

        InfoHandler selected = dispatcher.find(infoPackage).orElseThrow();
        assertTrue(dispatcher.unregister(new InfoPackageHeaderValue("CONFERENCE")));
        assertFalse(dispatcher.find(infoPackage).isPresent());
        assertEquals(200, selected.onInfo(new InfoRequest(
                infoPackage,
                SipHeaders.empty(),
                SipBody.empty()
        )).toCompletableFuture().join().statusCode());
    }
}
