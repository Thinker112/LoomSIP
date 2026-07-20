package org.loomsip.transaction.noninvite;

import org.loomsip.message.SipRequest;
import org.loomsip.transaction.SipTransaction;
import org.loomsip.transaction.TransactionKey;
import org.loomsip.transaction.TransactionMessageSender;
import org.loomsip.transaction.TransportReliability;
import org.loomsip.transaction.event.ApplicationRequest;
import org.loomsip.transaction.event.ResponseReceived;
import org.loomsip.transaction.event.TimerExpired;
import org.loomsip.transaction.event.TransactionEvent;
import org.loomsip.transaction.event.TransactionShutdown;
import org.loomsip.transaction.event.TransportFailed;
import org.loomsip.transaction.timer.SipScheduler;
import org.loomsip.transaction.timer.SipTimer;
import org.loomsip.transaction.timer.SipTimerConfig;
import org.loomsip.transport.TransportEndpoint;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * RFC 3261 Non-INVITE Client Transaction implementing Timer E, F, and K.
 *
 * <pre>{@code
 * INITIAL --ApplicationRequest--> TRYING --1xx--> PROCEEDING
 *                                  |                 |
 *                                  +----final--------+
 *                                           |
 *                                           v
 *                                       COMPLETED
 *                                           |
 *                                      Timer K / reliable
 *                                           v
 *                                       TERMINATED
 *
 * Timer E: retransmit request       Timer F: total timeout
 * }</pre>
 */
final class NonInviteClientTransaction extends AbstractNonInviteTransaction
        implements ClientTransactionHandle {

    private final SipRequest request;
    private final TransportEndpoint target;
    private final TransportReliability reliability;
    private final SipTimerConfig timerConfig;
    private final NonInviteClientListener listener;

    private volatile NonInviteClientState state = NonInviteClientState.INITIAL;
    private Duration timerEInterval;

    NonInviteClientTransaction(
            TransactionKey key,
            SipRequest request,
            TransportEndpoint target,
            TransportReliability reliability,
            TransactionMessageSender sender,
            SipTimerConfig timerConfig,
            SipScheduler scheduler,
            Executor transactionExecutor,
            Executor callbackExecutor,
            NonInviteTransactionConfig config,
            NonInviteClientListener listener,
            Consumer<? super SipTransaction> terminationCallback
    ) {
        super(
                key,
                sender,
                scheduler,
                transactionExecutor,
                callbackExecutor,
                config,
                terminationCallback,
                listener::onLayerError
        );
        this.request = Objects.requireNonNull(request, "request");
        this.target = Objects.requireNonNull(target, "target");
        this.reliability = Objects.requireNonNull(reliability, "reliability");
        this.timerConfig = Objects.requireNonNull(timerConfig, "timerConfig");
        this.listener = Objects.requireNonNull(listener, "listener");
    }

    void start() {
        submit(new ApplicationRequest(request, target));
    }

    void receive(ResponseReceived response) {
        submit(response);
    }

    void shutdown() {
        submit(new TransactionShutdown());
    }

    @Override
    public NonInviteClientState state() {
        return state;
    }

    @Override
    public SipRequest originalRequest() {
        return request;
    }

    @Override
    public CompletionStage<Void> terminated() {
        return terminationStage();
    }

    @Override
    protected void handleEvent(TransactionEvent event) {
        if (event instanceof TransactionShutdown) {
            transitionToTerminated();
            return;
        }
        if (state == NonInviteClientState.TERMINATED) {
            return;
        }
        if (event instanceof ApplicationRequest applicationRequest) {
            handleStart(applicationRequest);
        } else if (event instanceof ResponseReceived responseReceived) {
            handleResponse(responseReceived);
        } else if (event instanceof TimerExpired timerExpired) {
            handleTimer(timerExpired);
        } else if (event instanceof TransportFailed transportFailed) {
            handleTransportFailure(transportFailed.cause());
        }
    }

    @Override
    protected void handleInfrastructureFailure(Throwable cause) {
        reportInfrastructureFailure(cause);
        if (state != NonInviteClientState.TERMINATED) {
            state = NonInviteClientState.TERMINATED;
            terminate(() -> listener.onTerminated(this));
        }
    }

    private void handleStart(ApplicationRequest event) {
        if (state != NonInviteClientState.INITIAL) {
            return;
        }
        state = NonInviteClientState.TRYING;
        sendMessage(event.request(), event.target());
        if (reliability == TransportReliability.UNRELIABLE) {
            timerEInterval = timerConfig.t1();
            timers().start(SipTimer.E, timerEInterval);
        }
        timers().start(SipTimer.F, timerConfig.sixtyFourT1());
    }

    private void handleResponse(ResponseReceived event) {
        int status = event.response().statusCode();
        if (status < 200) {
            if (state == NonInviteClientState.TRYING) {
                state = NonInviteClientState.PROCEEDING;
            }
            if (state == NonInviteClientState.PROCEEDING) {
                notifyTu(() -> listener.onResponse(this, event.response(), event.context()));
            }
            return;
        }
        if (state != NonInviteClientState.TRYING && state != NonInviteClientState.PROCEEDING) {
            return;
        }

        timers().cancel(SipTimer.E);
        timers().cancel(SipTimer.F);
        state = NonInviteClientState.COMPLETED;
        notifyTu(() -> listener.onResponse(this, event.response(), event.context()));
        if (reliability == TransportReliability.UNRELIABLE) {
            timers().start(SipTimer.K, timerConfig.t4());
        } else {
            transitionToTerminated();
        }
    }

    private void handleTimer(TimerExpired event) {
        if (!timers().consumeIfCurrent(event.timer(), event.generation())) {
            return;
        }
        switch (event.timer()) {
            case E -> handleTimerE();
            case F -> {
                notifyTu(() -> listener.onTimeout(this, SipTimer.F));
                transitionToTerminated();
            }
            case K -> transitionToTerminated();
            default -> {
            }
        }
    }

    private void handleTimerE() {
        if (state != NonInviteClientState.TRYING && state != NonInviteClientState.PROCEEDING) {
            return;
        }
        sendMessage(request, target);
        if (state == NonInviteClientState.TRYING) {
            timerEInterval = minimum(timerEInterval.multipliedBy(2), timerConfig.t2());
        } else {
            timerEInterval = timerConfig.t2();
        }
        timers().start(SipTimer.E, timerEInterval);
    }

    private void handleTransportFailure(Throwable cause) {
        if (state == NonInviteClientState.COMPLETED) {
            return;
        }
        notifyTu(() -> listener.onTransportFailure(this, cause));
        transitionToTerminated();
    }

    private void transitionToTerminated() {
        if (state == NonInviteClientState.TERMINATED) {
            return;
        }
        state = NonInviteClientState.TERMINATED;
        terminate(() -> listener.onTerminated(this));
    }

    private static Duration minimum(Duration first, Duration second) {
        return first.compareTo(second) <= 0 ? first : second;
    }
}
