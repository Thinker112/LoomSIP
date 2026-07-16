package org.loomsip.transaction.invite;

import org.loomsip.message.SipRequest;
import org.loomsip.message.SipResponse;
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
import org.loomsip.transaction.event.TransportSucceeded;
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
 * RFC 3261 INVITE Client Transaction implementing Timer A, B, and D.
 *
 * <pre>{@code
 * INITIAL --INVITE--> CALLING --1xx--> PROCEEDING
 *                         |                 |
 *                         +----2xx----------+--> ACCEPTED --Timer M--> TERMINATED
 *                         |                 |
 *                         +----300-699------+--> COMPLETED
 *                                                  |
 *                                  Timer D / reliable ACK write
 *                                                  v
 *                                             TERMINATED
 *
 * Timer A: retransmit INVITE       Timer B: total timeout
 * }</pre>
 */
final class InviteClientTransaction extends AbstractInviteTransaction implements InviteClientHandle {

    private static final Duration TIMER_D_UNRELIABLE = Duration.ofSeconds(32);

    private final SipRequest invite;
    private final TransportEndpoint target;
    private final TransportReliability reliability;
    private final SipTimerConfig timerConfig;
    private final InviteClientListener listener;

    private volatile InviteClientState state = InviteClientState.INITIAL;
    private Duration timerAInterval;
    private long finalAckOperationId;

    InviteClientTransaction(
            TransactionKey key,
            SipRequest invite,
            TransportEndpoint target,
            TransportReliability reliability,
            TransactionMessageSender sender,
            SipTimerConfig timerConfig,
            SipScheduler scheduler,
            Executor transactionExecutor,
            Executor callbackExecutor,
            InviteTransactionConfig config,
            InviteClientListener listener,
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
        this.invite = Objects.requireNonNull(invite, "invite");
        this.target = Objects.requireNonNull(target, "target");
        this.reliability = Objects.requireNonNull(reliability, "reliability");
        this.timerConfig = Objects.requireNonNull(timerConfig, "timerConfig");
        this.listener = Objects.requireNonNull(listener, "listener");
    }

    void start() {
        submit(new ApplicationRequest(invite, target));
    }

    void receive(ResponseReceived response) {
        submit(response);
    }

    void shutdown() {
        submit(new TransactionShutdown());
    }

    boolean canCancel() {
        return state == InviteClientState.CALLING || state == InviteClientState.PROCEEDING;
    }

    SipRequest originalInvite() {
        return invite;
    }

    TransportEndpoint target() {
        return target;
    }

    @Override
    public InviteClientState state() {
        return state;
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
        if (state == InviteClientState.TERMINATED) {
            return;
        }
        if (event instanceof ApplicationRequest applicationRequest) {
            handleStart(applicationRequest);
        } else if (event instanceof ResponseReceived responseReceived) {
            handleResponse(responseReceived);
        } else if (event instanceof TimerExpired timerExpired) {
            handleTimer(timerExpired);
        } else if (event instanceof TransportSucceeded transportSucceeded) {
            handleTransportSuccess(transportSucceeded.operationId());
        } else if (event instanceof TransportFailed transportFailed) {
            handleTransportFailure(transportFailed.cause());
        }
    }

    @Override
    protected void handleInfrastructureFailure(Throwable cause) {
        reportInfrastructureFailure(cause);
        if (state != InviteClientState.TERMINATED) {
            state = InviteClientState.TERMINATED;
            terminate(() -> listener.onTerminated(this));
        }
    }

    private void handleStart(ApplicationRequest event) {
        if (state != InviteClientState.INITIAL) {
            return;
        }
        state = InviteClientState.CALLING;
        sendMessage(event.request(), event.target());
        if (reliability == TransportReliability.UNRELIABLE) {
            timerAInterval = timerConfig.t1();
            timers().start(SipTimer.A, timerAInterval);
        }
        timers().start(SipTimer.B, timerConfig.sixtyFourT1());
    }

    private void handleResponse(ResponseReceived event) {
        int status = event.response().statusCode();
        if (status < 200) {
            if (state == InviteClientState.CALLING) {
                timers().cancel(SipTimer.A);
                state = InviteClientState.PROCEEDING;
            }
            if (state == InviteClientState.PROCEEDING) {
                notifyTu(() -> listener.onResponse(this, event.response(), event.context()));
            }
            return;
        }
        if (status < 300) {
            if (state == InviteClientState.CALLING || state == InviteClientState.PROCEEDING) {
                timers().cancel(SipTimer.A);
                timers().cancel(SipTimer.B);
                state = InviteClientState.ACCEPTED;
                timers().start(SipTimer.M, timerConfig.sixtyFourT1());
            }
            if (state == InviteClientState.ACCEPTED) {
                notifyTu(() -> listener.onResponse(this, event.response(), event.context()));
            }
            return;
        }
        if (state == InviteClientState.COMPLETED) {
            sendMessage(InviteAcknowledgements.createNon2xxAck(invite, event.response()), target);
            return;
        }
        if (state != InviteClientState.CALLING && state != InviteClientState.PROCEEDING) {
            return;
        }

        timers().cancel(SipTimer.A);
        timers().cancel(SipTimer.B);
        SipRequest non2xxAck = InviteAcknowledgements.createNon2xxAck(invite, event.response());
        state = InviteClientState.COMPLETED;
        finalAckOperationId = sendMessage(non2xxAck, target);
        notifyTu(() -> listener.onResponse(this, event.response(), event.context()));
        if (reliability == TransportReliability.UNRELIABLE) {
            timers().start(SipTimer.D, TIMER_D_UNRELIABLE);
        }
    }

    private void handleTimer(TimerExpired event) {
        if (!timers().consumeIfCurrent(event.timer(), event.generation())) {
            return;
        }
        switch (event.timer()) {
            case A -> handleTimerA();
            case B -> {
                if (state == InviteClientState.CALLING || state == InviteClientState.PROCEEDING) {
                    notifyTu(() -> listener.onTimeout(this, SipTimer.B));
                    transitionToTerminated();
                }
            }
            case D -> transitionToTerminated();
            case M -> transitionToTerminated();
            default -> {
            }
        }
    }

    private void handleTimerA() {
        if (state != InviteClientState.CALLING) {
            return;
        }
        sendMessage(invite, target);
        timerAInterval = timerAInterval.multipliedBy(2);
        timers().start(SipTimer.A, timerAInterval);
    }

    private void handleTransportSuccess(long operationId) {
        if (reliability == TransportReliability.RELIABLE
                && state == InviteClientState.COMPLETED
                && operationId == finalAckOperationId) {
            transitionToTerminated();
        }
    }

    private void handleTransportFailure(Throwable cause) {
        if (state == InviteClientState.ACCEPTED) {
            return;
        }
        notifyTu(() -> listener.onTransportFailure(this, cause));
        transitionToTerminated();
    }

    private void transitionToTerminated() {
        if (state == InviteClientState.TERMINATED) {
            return;
        }
        state = InviteClientState.TERMINATED;
        terminate(() -> listener.onTerminated(this));
    }
}
