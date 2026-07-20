package org.loomsip.dialog;

import org.junit.jupiter.api.Test;
import org.loomsip.message.SipHeaders;
import org.loomsip.message.SipMethod;
import org.loomsip.message.SipRequest;
import org.loomsip.message.SipResponse;
import org.loomsip.message.SipUri;
import org.loomsip.message.header.RAckHeaderValue;
import org.loomsip.message.header.SipHeaderValues;
import org.loomsip.transaction.TransportReliability;
import org.loomsip.transaction.timer.SipTimerConfig;
import org.loomsip.transaction.timer.VirtualSipScheduler;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReliableProvisionalManagerTest {

    @Test
    void uasRetransmitsUntilMatchingPrackThenStops() throws Exception {
        VirtualSipScheduler scheduler = new VirtualSipScheduler();
        ReliableProvisionalManager manager = manager(scheduler);
        SipRequest invite = invite();
        DialogId id = new DialogId("call@example.com", "server-tag", "client-tag");
        AtomicReference<SipResponse> prepared = new AtomicReference<>();
        List<SipResponse> retransmissions = new ArrayList<>();

        SipResponse response = await(manager.registerUasResponse(
                id,
                invite,
                provisional(),
                TransportReliability.UNRELIABLE,
                () -> retransmissions.add(prepared.get())
        ));
        prepared.set(response);

        assertEquals("1", response.headers().firstValue("RSeq").orElseThrow());
        assertTrue(response.headers().firstValue("Require").orElseThrow().contains("100rel"));
        scheduler.advanceBy(SipTimerConfig.DEFAULT.t1());
        assertEquals(List.of(response), retransmissions);

        SipRequest prack = prack(new RAckHeaderValue(1, 10, SipMethod.INVITE));
        assertEquals(PrackValidation.ACCEPTED, await(manager.acceptPrack(id, prack)));
        scheduler.advanceBy(SipTimerConfig.DEFAULT.t2());
        assertEquals(1, retransmissions.size());
        assertEquals(0, scheduler.pendingCount());
    }

    @Test
    void rejectsMismatchedPrackAndDeduplicatesUacRseq() {
        VirtualSipScheduler scheduler = new VirtualSipScheduler();
        ReliableProvisionalManager manager = manager(scheduler);
        SipRequest invite = invite();
        DialogId uas = new DialogId("call@example.com", "server-tag", "client-tag");
        AtomicReference<SipResponse> prepared = new AtomicReference<>();
        SipResponse response = await(manager.registerUasResponse(
                uas,
                invite,
                provisional(),
                TransportReliability.RELIABLE,
                () -> {
                }
        ));
        prepared.set(response);
        assertEquals(PrackValidation.MISMATCH,
                await(manager.acceptPrack(uas, prack(new RAckHeaderValue(2, 10, SipMethod.INVITE)))));

        DialogId uac = new DialogId("call@example.com", "client-tag", "server-tag");
        Optional<RAckHeaderValue> first = await(manager.receiveUacResponse(uac, invite, response));
        assertEquals(new RAckHeaderValue(1, 10, SipMethod.INVITE), first.orElseThrow());
        assertFalse(await(manager.receiveUacResponse(uac, invite, response)).isPresent());
    }

    @Test
    void rejectsSecondOutstandingUasReliableResponse() {
        VirtualSipScheduler scheduler = new VirtualSipScheduler();
        ReliableProvisionalManager manager = manager(scheduler);
        DialogId id = new DialogId("call@example.com", "server-tag", "client-tag");
        await(manager.registerUasResponse(
                id,
                invite(),
                provisional(),
                TransportReliability.UNRELIABLE,
                () -> {
                }
        ));

        assertThrows(java.util.concurrent.CompletionException.class, () -> await(
                manager.registerUasResponse(
                        id,
                        invite(),
                        provisional(),
                        TransportReliability.UNRELIABLE,
                        () -> {
                        }
                )
        ));
    }

    private static ReliableProvisionalManager manager(VirtualSipScheduler scheduler) {
        return new ReliableProvisionalManager(
                new ReliableProvisionalConfig(8, 16, 4),
                scheduler,
                SipTimerConfig.DEFAULT,
                Runnable::run,
                failure -> {
                    throw new AssertionError(failure);
                }
        );
    }

    private static SipRequest invite() {
        return new SipRequest(
                SipMethod.INVITE,
                SipUri.parse("sip:bob@example.com"),
                headers("10 INVITE")
        );
    }

    private static SipResponse provisional() {
        return new SipResponse(183, "Session Progress", headers("10 INVITE").toBuilder()
                .add("Require", "100rel")
                .build());
    }

    private static SipRequest prack(RAckHeaderValue rack) {
        return new SipRequest(
                SipMethod.PRACK,
                SipUri.parse("sip:bob@example.com"),
                headers("11 PRACK").toBuilder().add("RAck", rack.wireValue()).build()
        );
    }

    private static SipHeaders headers(String cseq) {
        return SipHeaders.builder()
                .add("Via", "SIP/2.0/UDP client.example.com;branch=z9hG4bK-1")
                .add("From", "<sip:alice@example.com>;tag=client-tag")
                .add("To", "<sip:bob@example.com>;tag=server-tag")
                .add("Call-ID", "call@example.com")
                .add("CSeq", cseq)
                .build();
    }

    private static <T> T await(CompletionStage<T> stage) {
        return stage.toCompletableFuture().join();
    }
}
