package org.loomsip.transaction.invite;

import org.loomsip.message.SipMethod;
import org.loomsip.message.SipRequest;
import org.loomsip.message.SipResponse;
import org.loomsip.message.header.CSeqHeaderValue;
import org.loomsip.message.header.SipHeaderValueException;
import org.loomsip.message.header.SipHeaderValues;
import org.loomsip.transaction.SipTransaction;
import org.loomsip.transaction.TransactionKey;
import org.loomsip.transaction.TransactionMessageSender;
import org.loomsip.transaction.TransportReliability;
import org.loomsip.transaction.event.ApplicationResponse;
import org.loomsip.transaction.event.CancelRequested;
import org.loomsip.transaction.event.RequestReceived;
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
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * RFC 3261 INVITE Server Transaction implementing Timer G, H, and I.
 *
 * <pre>{@code
 * INITIAL --INVITE--> PROCEEDING --2xx--> ACCEPTED --Timer L--> TERMINATED
 *                          |
 *                       300-699
 *                          v
 *                      COMPLETED --ACK--> CONFIRMED
 *                          |                  |
 *                       Timer H            Timer I
 *                          |                  |
 *                          +-------> TERMINATED
 *
 * Timer G: retransmit non-2xx response
 * }</pre>
 */
final class InviteServerTransaction extends AbstractInviteTransaction implements InviteServerHandle {

    private final TransportReliability reliability;
    private final SipTimerConfig timerConfig;
    private final InviteServerListener listener;

    private volatile InviteServerState state = InviteServerState.INITIAL;
    private SipRequest invite;
    private TransportEndpoint responseTarget;
    private SipResponse lastResponse;
    private Duration timerGInterval;
    private boolean cancellationNotified;
    private final Set<Long> acceptedResponseOperations = new HashSet<>();

    InviteServerTransaction(
            TransactionKey key,
            TransportReliability reliability,
            TransactionMessageSender sender,
            SipTimerConfig timerConfig,
            SipScheduler scheduler,
            Executor transactionExecutor,
            Executor callbackExecutor,
            InviteTransactionConfig config,
            InviteServerListener listener,
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
        this.reliability = Objects.requireNonNull(reliability, "reliability");
        this.timerConfig = Objects.requireNonNull(timerConfig, "timerConfig");
        this.listener = Objects.requireNonNull(listener, "listener");
    }

    void receive(RequestReceived event) {
        submit(event);
    }

    void shutdown() {
        submit(new TransactionShutdown());
    }

    void requestCancellation(CancelRequested event) {
        submit(event);
    }

    boolean canConsumeAck() {
        return state == InviteServerState.COMPLETED || state == InviteServerState.CONFIRMED;
    }

    @Override
    public InviteServerState state() {
        return state;
    }

    @Override
    public void sendResponse(SipResponse response) {
        submit(new ApplicationResponse(Objects.requireNonNull(response, "response")));
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
        if (state == InviteServerState.TERMINATED) {
            return;
        }
        if (event instanceof RequestReceived requestReceived) {
            handleRequest(requestReceived);
        } else if (event instanceof CancelRequested cancelRequested) {
            handleCancel(cancelRequested);
        } else if (event instanceof ApplicationResponse applicationResponse) {
            handleResponse(applicationResponse.response());
        } else if (event instanceof TimerExpired timerExpired) {
            handleTimer(timerExpired);
        } else if (event instanceof TransportSucceeded transportSucceeded) {
            acceptedResponseOperations.remove(transportSucceeded.operationId());
        } else if (event instanceof TransportFailed transportFailed) {
            handleTransportFailure(transportFailed);
        }
    }

    @Override
    protected void handleInfrastructureFailure(Throwable cause) {
        reportInfrastructureFailure(cause);
        if (state != InviteServerState.TERMINATED) {
            state = InviteServerState.TERMINATED;
            terminate(() -> listener.onTerminated(this));
        }
    }

    private void handleRequest(RequestReceived event) {
        if (state == InviteServerState.INITIAL) {
            invite = event.request();
            responseTarget = new TransportEndpoint(
                    event.context().protocol(),
                    event.context().remoteAddress()
            );
            state = InviteServerState.PROCEEDING;
            notifyTu(() -> listener.onInvite(this, event.request(), event.context()));
            return;
        }
        if (SipMethod.ACK.equals(event.request().method())) {
            handleAck(event);
            return;
        }
        if ((state == InviteServerState.PROCEEDING || state == InviteServerState.COMPLETED)
                && lastResponse != null) {
            sendMessage(lastResponse, responseTarget);
        }
    }

    private void handleResponse(SipResponse response) {
        if (state == InviteServerState.ACCEPTED) {
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                validateResponseCorrelation(response);
                acceptedResponseOperations.add(sendMessage(response, responseTarget));
            }
            return;
        }
        if (state != InviteServerState.PROCEEDING) {
            return;
        }
        validateResponseCorrelation(response);
        lastResponse = response;
        if (response.statusCode() < 200) {
            acceptedResponseOperations.add(sendMessage(response, responseTarget));
            return;
        }

        if (response.statusCode() < 300) {
            state = InviteServerState.ACCEPTED;
            acceptedResponseOperations.add(sendMessage(response, responseTarget));
            timers().start(SipTimer.L, timerConfig.sixtyFourT1());
            return;
        }

        state = InviteServerState.COMPLETED;
        sendMessage(response, responseTarget);

        if (reliability == TransportReliability.UNRELIABLE) {
            timerGInterval = timerConfig.t1();
            timers().start(SipTimer.G, timerGInterval);
        }
        timers().start(SipTimer.H, timerConfig.sixtyFourT1());
    }

    private void handleCancel(CancelRequested event) {
        if (state == InviteServerState.PROCEEDING && !cancellationNotified) {
            cancellationNotified = true;
            notifyTu(() -> listener.onCancel(this, event.cancel(), event.context()));
        }
    }

    private void handleAck(RequestReceived event) {
        if (state != InviteServerState.COMPLETED) {
            return;
        }
        timers().cancel(SipTimer.G);
        timers().cancel(SipTimer.H);
        state = InviteServerState.CONFIRMED;
        notifyTu(() -> listener.onAck(this, event.request(), event.context()));
        if (reliability == TransportReliability.UNRELIABLE) {
            timers().start(SipTimer.I, timerConfig.t4());
        } else {
            transitionToTerminated();
        }
    }

    private void handleTimer(TimerExpired event) {
        if (!timers().consumeIfCurrent(event.timer(), event.generation())) {
            return;
        }
        switch (event.timer()) {
            case G -> handleTimerG();
            case H -> {
                if (state == InviteServerState.COMPLETED) {
                    notifyTu(() -> listener.onTimeout(this, SipTimer.H));
                    transitionToTerminated();
                }
            }
            case I -> transitionToTerminated();
            case L -> transitionToTerminated();
            default -> {
            }
        }
    }

    private void handleTimerG() {
        if (state != InviteServerState.COMPLETED) {
            return;
        }
        sendMessage(lastResponse, responseTarget);
        timerGInterval = minimum(timerGInterval.multipliedBy(2), timerConfig.t2());
        timers().start(SipTimer.G, timerGInterval);
    }

    private void handleTransportFailure(TransportFailed failure) {
        if (state == InviteServerState.ACCEPTED
                && !acceptedResponseOperations.remove(failure.operationId())) {
            return;
        }
        notifyTu(() -> listener.onTransportFailure(this, failure.cause()));
        transitionToTerminated();
    }

    private void validateResponseCorrelation(SipResponse response) {
        try {
            CSeqHeaderValue requestCSeq = SipHeaderValues.cseq(invite.headers());
            CSeqHeaderValue responseCSeq = SipHeaderValues.cseq(response.headers());
            String requestCallId = SipHeaderValues.callId(invite.headers());
            String responseCallId = SipHeaderValues.callId(response.headers());
            if (!requestCSeq.equals(responseCSeq) || !requestCallId.equals(responseCallId)) {
                throw new IllegalArgumentException("response does not match INVITE transaction CSeq/Call-ID");
            }
        } catch (SipHeaderValueException exception) {
            throw new IllegalArgumentException("response routing headers are malformed", exception);
        }
    }

    private void transitionToTerminated() {
        if (state == InviteServerState.TERMINATED) {
            return;
        }
        state = InviteServerState.TERMINATED;
        terminate(() -> listener.onTerminated(this));
    }

    private static Duration minimum(Duration first, Duration second) {
        return first.compareTo(second) <= 0 ? first : second;
    }
}
