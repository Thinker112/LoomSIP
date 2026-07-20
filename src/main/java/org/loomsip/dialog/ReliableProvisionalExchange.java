package org.loomsip.dialog;

import org.loomsip.concurrent.SerialMailbox;
import org.loomsip.message.SipHeader;
import org.loomsip.message.SipHeaders;
import org.loomsip.message.SipMethod;
import org.loomsip.message.SipRequest;
import org.loomsip.message.SipResponse;
import org.loomsip.message.header.CSeqHeaderValue;
import org.loomsip.message.header.RAckHeaderValue;
import org.loomsip.message.header.RSeqHeaderValue;
import org.loomsip.message.header.SipExtensionSupport;
import org.loomsip.message.header.SipHeaderValueException;
import org.loomsip.message.header.SipHeaderValues;
import org.loomsip.transaction.TransportReliability;
import org.loomsip.transaction.timer.Cancellable;
import org.loomsip.transaction.timer.SipScheduler;
import org.loomsip.transaction.timer.SipTimerConfig;

import java.time.Duration;
import java.util.HashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * Serialized RFC 3262 state for reliable provisional responses in one Dialog.
 *
 * <pre>{@code
 * UAS provisional response / inbound PRACK / timer
 *                     |
 *                     v
 *       Reliable Provisional Exchange Mailbox
 *          |                  |             |
 *          v                  v             v
 *      add RSeq          verify RAck    retransmit response
 * }</pre>
 *
 * <p>One exchange belongs to exactly one Dialog and its originating INVITE.
 * The exchange owns only RSeq/RAck correlation and reliable-response timer
 * state. INVITE and PRACK transaction state remains in their existing
 * transaction state machines.</p>
 */
final class ReliableProvisionalExchange implements AutoCloseable {

    private final SipRequest invite;
    private final SipScheduler scheduler;
    private final SipTimerConfig timerConfig;
    private final Consumer<? super Throwable> failureListener;
    private final int maxUacRSeqs;
    private final SerialMailbox<Event> mailbox;

    private long nextRSeq = 1;
    private PendingUas pendingUas;
    private final Set<Long> receivedUacRSeqs = new HashSet<>();
    private boolean closed;

    ReliableProvisionalExchange(
            SipRequest invite,
            SipScheduler scheduler,
            SipTimerConfig timerConfig,
            Executor executor,
            Consumer<? super Throwable> failureListener,
            int mailboxCapacity,
            int maxUacRSeqs
    ) {
        this.invite = Objects.requireNonNull(invite, "invite");
        if (!SipMethod.INVITE.equals(invite.method())) {
            throw new IllegalArgumentException("reliable provisional exchange requires an INVITE");
        }
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.timerConfig = Objects.requireNonNull(timerConfig, "timerConfig");
        this.failureListener = Objects.requireNonNull(failureListener, "failureListener");
        if (maxUacRSeqs <= 0) {
            throw new IllegalArgumentException("maxUacRSeqs must be positive");
        }
        this.maxUacRSeqs = maxUacRSeqs;
        mailbox = new SerialMailbox<>(
                Objects.requireNonNull(executor, "executor"),
                this::handle,
                this::reportFailure,
                mailboxCapacity
        );
    }

    CompletionStage<SipResponse> registerUas(
            SipResponse response,
            TransportReliability reliability,
            Runnable retransmit
    ) {
        CompletableFuture<SipResponse> result = new CompletableFuture<>();
        submit(new UasResponseRegistered(
                Objects.requireNonNull(response, "response"),
                Objects.requireNonNull(reliability, "reliability"),
                Objects.requireNonNull(retransmit, "retransmit"),
                result
        ), result);
        return result.minimalCompletionStage();
    }

    CompletionStage<PrackValidation> acceptPrack(SipRequest prack) {
        CompletableFuture<PrackValidation> result = new CompletableFuture<>();
        submit(new PrackReceived(Objects.requireNonNull(prack, "prack"), result), result);
        return result.minimalCompletionStage();
    }

    CompletionStage<Optional<RAckHeaderValue>> receiveUac(SipResponse response) {
        CompletableFuture<Optional<RAckHeaderValue>> result = new CompletableFuture<>();
        submit(new UacResponseReceived(Objects.requireNonNull(response, "response"), result), result);
        return result.minimalCompletionStage();
    }

    @Override
    public void close() {
        try {
            mailbox.submit(new ExchangeClosed());
        } catch (RuntimeException ignored) {
            // Already closed or closing.
        }
    }

    private void handle(Event event) {
        if (event instanceof UasResponseRegistered registered) {
            registerUasResponse(registered);
        } else if (event instanceof PrackReceived received) {
            handlePrack(received);
        } else if (event instanceof UacResponseReceived received) {
            handleUacResponse(received);
        } else if (event instanceof RetransmitTimerExpired expired) {
            handleRetransmit(expired);
        } else if (event instanceof ExchangeClosed) {
            closed = true;
            cancelPendingTimer();
            mailbox.close();
        }
    }

    private void registerUasResponse(UasResponseRegistered registered) {
        if (closed) {
            registered.result().completeExceptionally(new ReliableProvisionalException("exchange is closed"));
            return;
        }
        if (pendingUas != null) {
            registered.result().completeExceptionally(new ReliableProvisionalException(
                    "previous reliable provisional response still awaits PRACK"
            ));
            return;
        }
        try {
            validateProvisionalResponse(registered.response());
            if (registered.response().headers().contains("RSeq")) {
                throw new ReliableProvisionalException("application must not pre-set RSeq");
            }
            RSeqHeaderValue rseq = new RSeqHeaderValue(nextRSeq);
            nextRSeq = nextRSeq == RSeqHeaderValue.MAX_SEQUENCE_NUMBER ? 1 : nextRSeq + 1;
            SipHeaders headers = SipExtensionSupport.withAdded(
                    registered.response().headers(),
                    "Require",
                    SipExtensionSupport.RELIABLE_PROVISIONAL
            ).toBuilder().add("RSeq", rseq.wireValue()).build();
            SipResponse prepared = new SipResponse(
                    registered.response().version(),
                    registered.response().statusCode(),
                    registered.response().reasonPhrase(),
                    headers,
                    registered.response().body()
            );
            CSeqHeaderValue cseq = SipHeaderValues.cseq(invite.headers());
            pendingUas = new PendingUas(
                    prepared,
                    new RAckHeaderValue(rseq.sequenceNumber(), cseq.sequenceNumber(), cseq.method()),
                    registered.retransmit(),
                    registered.reliability(),
                    timerConfig.t1(),
                    0
            );
            if (registered.reliability() == TransportReliability.UNRELIABLE) {
                scheduleRetransmit();
            }
            registered.result().complete(prepared);
        } catch (Throwable cause) {
            registered.result().completeExceptionally(cause);
        }
    }

    private void handlePrack(PrackReceived received) {
        if (closed || pendingUas == null || !SipMethod.PRACK.equals(received.prack().method())) {
            received.result().complete(PrackValidation.MISMATCH);
            return;
        }
        try {
            RAckHeaderValue rack = SipHeaderValues.rack(received.prack().headers());
            if (!pendingUas.expectedRack().equals(rack)) {
                received.result().complete(PrackValidation.MISMATCH);
                return;
            }
            cancelPendingTimer();
            pendingUas = null;
            received.result().complete(PrackValidation.ACCEPTED);
        } catch (SipHeaderValueException exception) {
            received.result().complete(PrackValidation.MISMATCH);
        }
    }

    private void handleUacResponse(UacResponseReceived received) {
        if (closed) {
            received.result().completeExceptionally(new ReliableProvisionalException("exchange is closed"));
            return;
        }
        try {
            SipResponse response = received.response();
            if (!SipExtensionSupport.contains(
                    response.headers(),
                    "Require",
                    SipExtensionSupport.RELIABLE_PROVISIONAL
            )) {
                received.result().complete(Optional.empty());
                return;
            }
            validateProvisionalResponse(response);
            RSeqHeaderValue rseq = SipHeaderValues.rseq(response.headers());
            if (!receivedUacRSeqs.add(rseq.sequenceNumber())) {
                received.result().complete(Optional.empty());
                return;
            }
            if (receivedUacRSeqs.size() > maxUacRSeqs) {
                throw new ReliableProvisionalException("UAC reliable provisional RSeq limit reached");
            }
            CSeqHeaderValue inviteCSeq = SipHeaderValues.cseq(invite.headers());
            received.result().complete(Optional.of(new RAckHeaderValue(
                    rseq.sequenceNumber(),
                    inviteCSeq.sequenceNumber(),
                    inviteCSeq.method()
            )));
        } catch (Throwable cause) {
            received.result().completeExceptionally(cause);
        }
    }

    private void handleRetransmit(RetransmitTimerExpired expired) {
        PendingUas current = pendingUas;
        if (closed || current == null || current.generation() != expired.generation()) {
            return;
        }
        try {
            current.retransmit().run();
        } catch (Throwable cause) {
            reportFailure(cause);
        }
        Duration nextInterval = current.interval().multipliedBy(2);
        if (nextInterval.compareTo(timerConfig.t2()) > 0) {
            nextInterval = timerConfig.t2();
        }
        pendingUas = current.withTimer(nextInterval, current.generation() + 1);
        scheduleRetransmit();
    }

    private void scheduleRetransmit() {
        PendingUas current = pendingUas;
        if (current == null || current.reliability() != TransportReliability.UNRELIABLE) {
            return;
        }
        long generation = current.generation() + 1;
        pendingUas = current.withTimer(current.interval(), generation);
        Cancellable timer = scheduler.schedule(
                current.interval(),
                () -> submitTimer(new RetransmitTimerExpired(generation))
        );
        pendingUas = pendingUas.withTimer(timer, generation);
    }

    private void cancelPendingTimer() {
        if (pendingUas != null && pendingUas.timer() != null) {
            pendingUas.timer().cancel();
        }
    }

    private void validateProvisionalResponse(SipResponse response) throws SipHeaderValueException {
        if (response.statusCode() <= 100 || response.statusCode() >= 200) {
            throw new ReliableProvisionalException("reliable response must have status 101 through 199");
        }
        CSeqHeaderValue inviteCSeq = SipHeaderValues.cseq(invite.headers());
        CSeqHeaderValue responseCSeq = SipHeaderValues.cseq(response.headers());
        if (!inviteCSeq.equals(responseCSeq) || !SipMethod.INVITE.equals(responseCSeq.method())) {
            throw new ReliableProvisionalException("reliable provisional response must match INVITE CSeq");
        }
    }

    private void submitTimer(RetransmitTimerExpired event) {
        try {
            mailbox.submit(event);
        } catch (RuntimeException ignored) {
            // The exchange was closed before timer delivery.
        }
    }

    private void submit(Event event, CompletableFuture<?> result) {
        try {
            mailbox.submit(event);
        } catch (Throwable cause) {
            result.completeExceptionally(cause);
        }
    }

    private void reportFailure(Throwable cause) {
        try {
            failureListener.accept(cause);
        } catch (Throwable ignored) {
            System.getLogger(ReliableProvisionalExchange.class.getName()).log(
                    System.Logger.Level.WARNING,
                    "reliable provisional failure listener failed",
                    ignored
            );
        }
    }

    private sealed interface Event permits UasResponseRegistered, PrackReceived, UacResponseReceived,
            RetransmitTimerExpired, ExchangeClosed {
    }

    private record UasResponseRegistered(
            SipResponse response,
            TransportReliability reliability,
            Runnable retransmit,
            CompletableFuture<SipResponse> result
    ) implements Event {
    }

    private record PrackReceived(SipRequest prack, CompletableFuture<PrackValidation> result)
            implements Event {
    }

    private record UacResponseReceived(
            SipResponse response,
            CompletableFuture<Optional<RAckHeaderValue>> result
    ) implements Event {
    }

    private record RetransmitTimerExpired(long generation) implements Event {
    }

    private record ExchangeClosed() implements Event {
    }

    private record PendingUas(
            SipResponse response,
            RAckHeaderValue expectedRack,
            Runnable retransmit,
            TransportReliability reliability,
            Duration interval,
            long generation,
            Cancellable timer
    ) {
        private PendingUas(
                SipResponse response,
                RAckHeaderValue expectedRack,
                Runnable retransmit,
                TransportReliability reliability,
                Duration interval,
                long generation
        ) {
            this(response, expectedRack, retransmit, reliability, interval, generation, null);
        }

        private PendingUas withTimer(Duration newInterval, long newGeneration) {
            return new PendingUas(
                    response,
                    expectedRack,
                    retransmit,
                    reliability,
                    newInterval,
                    newGeneration,
                    null
            );
        }

        private PendingUas withTimer(Cancellable newTimer, long newGeneration) {
            return new PendingUas(
                    response,
                    expectedRack,
                    retransmit,
                    reliability,
                    interval,
                    newGeneration,
                    newTimer
            );
        }
    }
}
