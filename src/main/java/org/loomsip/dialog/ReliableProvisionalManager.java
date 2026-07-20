package org.loomsip.dialog;

import org.loomsip.message.SipRequest;
import org.loomsip.message.SipResponse;
import org.loomsip.message.header.RAckHeaderValue;
import org.loomsip.message.header.SipExtensionSupport;
import org.loomsip.transaction.TransportReliability;
import org.loomsip.transaction.timer.SipScheduler;
import org.loomsip.transaction.timer.SipTimerConfig;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * Bounded owner of per-Dialog RFC 3262 reliable provisional exchanges.
 *
 * <pre>{@code
 * DialogId
 *    |
 *    v
 * Reliable Provisional Manager
 *    |
 *    +--> Early Dialog A -> exchange / RSeq / RAck / timer
 *    +--> Early Dialog B -> exchange / RSeq / RAck / timer
 * }</pre>
 *
 * <p>Forked responses have distinct Dialog IDs and therefore never share RSeq,
 * RAck, or retransmission state. The manager does not own the scheduler or
 * executor supplied by the stack.</p>
 */
public final class ReliableProvisionalManager implements AutoCloseable {

    private final ReliableProvisionalConfig config;
    private final SipScheduler scheduler;
    private final SipTimerConfig timerConfig;
    private final Executor executor;
    private final Consumer<? super Throwable> failureListener;
    private final Map<DialogId, ReliableProvisionalExchange> exchanges = new ConcurrentHashMap<>();

    /**
     * Creates a manager with explicit resource bounds and execution dependencies.
     *
     * @param config exchange and mailbox limits
     * @param scheduler shared SIP timer scheduler
     * @param timerConfig retransmission timer constants
     * @param executor executor used for per-exchange mailbox drain tasks
     * @param failureListener reliable provisional infrastructure failure observer
     */
    public ReliableProvisionalManager(
            ReliableProvisionalConfig config,
            SipScheduler scheduler,
            SipTimerConfig timerConfig,
            Executor executor,
            Consumer<? super Throwable> failureListener
    ) {
        this.config = Objects.requireNonNull(config, "config");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.timerConfig = Objects.requireNonNull(timerConfig, "timerConfig");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.failureListener = Objects.requireNonNull(failureListener, "failureListener");
    }

    /**
     * Adds Require and RSeq to one UAS provisional response and starts its reliable timer.
     *
     * @param id UAS Early Dialog identity
     * @param invite original INVITE
     * @param response application provisional response
     * @param reliability response transport reliability
     * @param retransmit action that sends the prepared response without re-entering this manager
     * @return prepared response containing Require: 100rel and RSeq
     */
    public CompletionStage<SipResponse> registerUasResponse(
            DialogId id,
            SipRequest invite,
            SipResponse response,
            TransportReliability reliability,
            Runnable retransmit
    ) {
        return exchange(id, invite).thenCompose(exchange ->
                exchange.registerUas(response, reliability, retransmit)
        );
    }

    /**
     * Validates an inbound UAS PRACK RAck against the pending response for one Dialog.
     *
     * @param id UAS Dialog identity
     * @param prack inbound PRACK request
     * @return accepted only for the current RSeq/INVITE CSeq pair
     */
    public CompletionStage<PrackValidation> acceptPrack(DialogId id, SipRequest prack) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(prack, "prack");
        ReliableProvisionalExchange exchange = exchanges.get(id);
        return exchange == null
                ? CompletableFuture.completedFuture(PrackValidation.MISMATCH)
                : exchange.acceptPrack(prack);
    }

    /**
     * Correlates one UAC reliable provisional response and returns the PRACK RAck once.
     *
     * @param id UAC Early Dialog identity
     * @param invite original INVITE
     * @param response provisional response
     * @return empty when response is not reliable or its RSeq was already processed
     */
    public CompletionStage<Optional<RAckHeaderValue>> receiveUacResponse(
            DialogId id,
            SipRequest invite,
            SipResponse response
    ) {
        if (!SipExtensionSupport.contains(
                response.headers(),
                "Require",
                SipExtensionSupport.RELIABLE_PROVISIONAL
        )) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        return exchange(id, invite).thenCompose(exchange -> exchange.receiveUac(response));
    }

    /**
     * Releases one Dialog's reliable provisional state and cancels its timer.
     *
     * @param id Dialog identity
     */
    public void release(DialogId id) {
        ReliableProvisionalExchange exchange = exchanges.remove(Objects.requireNonNull(id, "id"));
        if (exchange != null) {
            exchange.close();
        }
    }

    /**
     * Returns the active exchange count.
     *
     * @return active reliable provisional exchange count
     */
    public int activeExchanges() {
        return exchanges.size();
    }

    /** Cancels all pending retransmission timers and releases exchange state. */
    @Override
    public void close() {
        exchanges.values().forEach(ReliableProvisionalExchange::close);
        exchanges.clear();
    }

    private CompletionStage<ReliableProvisionalExchange> exchange(DialogId id, SipRequest invite) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(invite, "invite");
        try {
            ReliableProvisionalExchange exchange = exchanges.computeIfAbsent(id, ignored -> {
                if (exchanges.size() >= config.exchanges()) {
                    throw new ReliableProvisionalException("reliable provisional exchange capacity reached");
                }
                return new ReliableProvisionalExchange(
                        invite,
                        scheduler,
                        timerConfig,
                        executor,
                        failureListener,
                        config.mailboxCapacity(),
                        config.maxUacRSeqs()
                );
            });
            return CompletableFuture.completedFuture(exchange);
        } catch (Throwable cause) {
            return CompletableFuture.failedFuture(cause);
        }
    }
}
