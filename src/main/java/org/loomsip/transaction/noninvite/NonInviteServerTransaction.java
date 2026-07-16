package org.loomsip.transaction.noninvite;

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

import java.util.Objects;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * RFC 3261 Non-INVITE Server Transaction implementing duplicate handling and Timer J.
 *
 * <pre>{@code
 * INITIAL --first request--> TRYING --1xx--> PROCEEDING
 *                                 |              |
 *                                 +----final-----+
 *                                         |
 *                                         v
 *                                     COMPLETED
 *                                         |
 *                               Timer J / reliable transport
 *                                         v
 *                                     TERMINATED
 *
 * Duplicate request in PROCEEDING/COMPLETED -> resend last response
 * }</pre>
 */
final class NonInviteServerTransaction extends AbstractNonInviteTransaction
        implements ServerTransactionHandle {

    private final TransportReliability reliability;
    private final SipTimerConfig timerConfig;
    private final NonInviteServerListener listener;

    private volatile NonInviteServerState state = NonInviteServerState.INITIAL;
    private SipRequest request;
    private TransportEndpoint responseTarget;
    private SipResponse lastResponse;
    private long finalResponseOperationId;

    NonInviteServerTransaction(
            TransactionKey key,
            TransportReliability reliability,
            TransactionMessageSender sender,
            SipTimerConfig timerConfig,
            SipScheduler scheduler,
            Executor transactionExecutor,
            Executor callbackExecutor,
            NonInviteTransactionConfig config,
            NonInviteServerListener listener,
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

    @Override
    public NonInviteServerState state() {
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
    void handleEvent(TransactionEvent event) {
        if (event instanceof TransactionShutdown) {
            transitionToTerminated();
            return;
        }
        if (state == NonInviteServerState.TERMINATED) {
            return;
        }
        if (event instanceof RequestReceived requestReceived) {
            handleRequest(requestReceived);
        } else if (event instanceof ApplicationResponse applicationResponse) {
            handleResponse(applicationResponse.response());
        } else if (event instanceof TimerExpired timerExpired) {
            handleTimer(timerExpired);
        } else if (event instanceof TransportSucceeded transportSucceeded) {
            handleTransportSuccess(transportSucceeded.operationId());
        } else if (event instanceof TransportFailed transportFailed) {
            handleTransportFailure(transportFailed.cause());
        }
    }

    @Override
    void handleInfrastructureFailure(Throwable cause) {
        reportInfrastructureFailure(cause);
        if (state != NonInviteServerState.TERMINATED) {
            state = NonInviteServerState.TERMINATED;
            terminate(() -> listener.onTerminated(this));
        }
    }

    private void handleRequest(RequestReceived event) {
        if (state == NonInviteServerState.INITIAL) {
            request = event.request();
            responseTarget = new TransportEndpoint(
                    event.context().protocol(),
                    event.context().remoteAddress()
            );
            state = NonInviteServerState.TRYING;
            notifyTu(() -> listener.onRequest(this, event.request(), event.context()));
            return;
        }
        if ((state == NonInviteServerState.PROCEEDING || state == NonInviteServerState.COMPLETED)
                && lastResponse != null) {
            sendMessage(lastResponse, responseTarget);
        }
    }

    private void handleResponse(SipResponse response) {
        if (state != NonInviteServerState.TRYING && state != NonInviteServerState.PROCEEDING) {
            return;
        }
        validateResponseCorrelation(response);
        lastResponse = response;
        long operationId = sendMessage(response, responseTarget);
        if (response.statusCode() < 200) {
            state = NonInviteServerState.PROCEEDING;
            return;
        }

        state = NonInviteServerState.COMPLETED;
        if (reliability == TransportReliability.UNRELIABLE) {
            timers().start(SipTimer.J, timerConfig.sixtyFourT1());
        } else {
            finalResponseOperationId = operationId;
        }
    }

    private void handleTimer(TimerExpired event) {
        if (!timers().consumeIfCurrent(event.timer(), event.generation())) {
            return;
        }
        if (event.timer() == SipTimer.J) {
            transitionToTerminated();
        }
    }

    private void handleTransportFailure(Throwable cause) {
        notifyTu(() -> listener.onTransportFailure(this, cause));
        transitionToTerminated();
    }

    private void handleTransportSuccess(long operationId) {
        if (reliability == TransportReliability.RELIABLE
                && state == NonInviteServerState.COMPLETED
                && operationId == finalResponseOperationId) {
            transitionToTerminated();
        }
    }

    private void validateResponseCorrelation(SipResponse response) {
        try {
            CSeqHeaderValue requestCSeq = SipHeaderValues.cseq(request.headers());
            CSeqHeaderValue responseCSeq = SipHeaderValues.cseq(response.headers());
            String requestCallId = SipHeaderValues.callId(request.headers());
            String responseCallId = SipHeaderValues.callId(response.headers());
            if (!requestCSeq.equals(responseCSeq) || !requestCallId.equals(responseCallId)) {
                throw new IllegalArgumentException("response does not match server transaction CSeq/Call-ID");
            }
        } catch (SipHeaderValueException exception) {
            throw new IllegalArgumentException("response routing headers are malformed", exception);
        }
    }

    private void transitionToTerminated() {
        if (state == NonInviteServerState.TERMINATED) {
            return;
        }
        state = NonInviteServerState.TERMINATED;
        terminate(() -> listener.onTerminated(this));
    }
}
