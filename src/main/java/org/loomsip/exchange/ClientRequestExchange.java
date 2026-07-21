package org.loomsip.exchange;

import org.loomsip.concurrent.MailboxClosedException;
import org.loomsip.concurrent.SerialMailbox;
import org.loomsip.message.SipRequest;
import org.loomsip.message.SipResponse;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Serialized lifecycle for one logical request spanning transaction attempts.
 *
 * <pre>{@code
 * Initial request / Authentication retry / Final response
 *                         |
 *                         v
 *              Client Request Exchange Mailbox
 *                         |
 *             +-----------+-----------+
 *             v                       v
 *      RequestAttemptFactory      Exchange completion
 *             |
 *             v
 *       one new Transaction
 * }</pre>
 *
 * <p>The exchange does not inspect authentication headers or transaction
 * states. A later authentication coordinator decides when to build a retry;
 * this component only orders attempts, applies the hard attempt limit, and
 * guarantees one terminal logical result.</p>
 *
 * @param <T> concrete transaction handle type
 */
public final class ClientRequestExchange<T> implements AutoCloseable {

    /** Default maximum number of queued exchange events. */
    public static final int DEFAULT_MAILBOX_CAPACITY = 64;

    private final SipRequest initialRequest;
    private final RequestAttemptFactory<T> attemptFactory;
    private final RequestRetryPolicy retryPolicy;
    private final Consumer<? super Throwable> failureListener;
    private final SerialMailbox<ClientRequestExchangeEvent<T>> mailbox;
    private final CompletableFuture<SipResponse> completion = new CompletableFuture<>();
    private final Set<CompletableFuture<?>> operations = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final AtomicBoolean closeRequested = new AtomicBoolean();

    private volatile ClientRequestExchangeState state = ClientRequestExchangeState.NEW;
    private volatile int attemptCount;
    private volatile RequestAttempt<T> currentAttempt;

    /**
     * Creates an exchange with the default retry and mailbox limits.
     *
     * @param initialRequest immutable first request
     * @param attemptFactory transaction attempt factory
     * @param executor executor used by on-demand mailbox drain tasks
     */
    public ClientRequestExchange(
            SipRequest initialRequest,
            RequestAttemptFactory<T> attemptFactory,
            Executor executor
    ) {
        this(
                initialRequest,
                attemptFactory,
                RequestRetryPolicy.DEFAULT,
                executor,
                failure -> {
                },
                DEFAULT_MAILBOX_CAPACITY
        );
    }

    /**
     * Creates an exchange with explicit retry, failure, and mailbox policies.
     *
     * @param initialRequest immutable first request
     * @param attemptFactory transaction attempt factory
     * @param retryPolicy hard total attempt limit
     * @param executor executor used by on-demand mailbox drain tasks
     * @param failureListener infrastructure and attempt-creation failure observer
     * @param mailboxCapacity maximum queued events excluding the current event
     */
    public ClientRequestExchange(
            SipRequest initialRequest,
            RequestAttemptFactory<T> attemptFactory,
            RequestRetryPolicy retryPolicy,
            Executor executor,
            Consumer<? super Throwable> failureListener,
            int mailboxCapacity
    ) {
        this.initialRequest = Objects.requireNonNull(initialRequest, "initialRequest");
        this.attemptFactory = Objects.requireNonNull(attemptFactory, "attemptFactory");
        this.retryPolicy = Objects.requireNonNull(retryPolicy, "retryPolicy");
        this.failureListener = Objects.requireNonNull(failureListener, "failureListener");
        mailbox = new SerialMailbox<>(
                Objects.requireNonNull(executor, "executor"),
                this::handleEvent,
                this::handleInfrastructureFailure,
                mailboxCapacity
        );
    }

    /**
     * Starts the initial transaction attempt exactly once.
     *
     * @return stage yielding the first started attempt
     */
    public CompletionStage<RequestAttempt<T>> start() {
        CompletableFuture<RequestAttempt<T>> result = new CompletableFuture<>();
        track(result);
        submit(new ExchangeStart<>(result), result);
        return result.minimalCompletionStage();
    }

    /**
     * Starts a new transaction attempt for an already rebuilt retry request.
     *
     * <p>The caller must obtain a new Via branch and CSeq from the appropriate
     * request owner before invoking this method.</p>
     *
     * @param request immutable retry request
     * @return stage yielding the newly started attempt
     */
    public CompletionStage<RequestAttempt<T>> retry(SipRequest request) {
        CompletableFuture<RequestAttempt<T>> result = new CompletableFuture<>();
        track(result);
        submit(new ExchangeRetry<>(Objects.requireNonNull(request, "request"), result), result);
        return result.minimalCompletionStage();
    }

    /**
     * Completes the logical request with its selected final response.
     *
     * @param response final response selected by the coordinator
     * @return stage completed after the terminal event is processed
     */
    public CompletionStage<Void> complete(SipResponse response) {
        CompletableFuture<Void> result = new CompletableFuture<>();
        track(result);
        submit(new ExchangeComplete<>(Objects.requireNonNull(response, "response"), result), result);
        return result.minimalCompletionStage();
    }

    /**
     * Fails the logical request without creating another attempt.
     *
     * @param cause terminal failure
     * @return stage completed after the terminal event is processed
     */
    public CompletionStage<Void> fail(Throwable cause) {
        CompletableFuture<Void> result = new CompletableFuture<>();
        track(result);
        submit(new ExchangeFailed<>(Objects.requireNonNull(cause, "cause"), result), result);
        return result.minimalCompletionStage();
    }

    /**
     * Returns the terminal logical response stage.
     *
     * @return final response, or exceptional completion on failure/close
     */
    public CompletionStage<SipResponse> completion() {
        return completion.minimalCompletionStage();
    }

    /**
     * Returns the latest externally visible lifecycle state.
     *
     * @return current exchange state
     */
    public ClientRequestExchangeState state() {
        return state;
    }

    /**
     * Returns the number of successfully started transaction attempts.
     *
     * @return started attempt count
     */
    public int attemptCount() {
        return attemptCount;
    }

    /**
     * Returns the most recently started attempt.
     *
     * @return current attempt, empty before start
     */
    public Optional<RequestAttempt<T>> currentAttempt() {
        return Optional.ofNullable(currentAttempt);
    }

    /**
     * Returns mailbox close completion.
     *
     * @return stage completed after all accepted events drain
     */
    public CompletionStage<Void> closed() {
        return mailbox.closed();
    }

    /**
     * Idempotently closes an unfinished exchange and rejects new events.
     */
    @Override
    public void close() {
        if (!closeRequested.compareAndSet(false, true)) {
            return;
        }
        if (state == ClientRequestExchangeState.NEW || state == ClientRequestExchangeState.ACTIVE) {
            state = ClientRequestExchangeState.CLOSED;
            completion.completeExceptionally(new ClientRequestExchangeClosedException());
        }
        completePendingExceptionally(new ClientRequestExchangeClosedException());
        mailbox.closeNow();
    }

    private void handleEvent(ClientRequestExchangeEvent<T> event) {
        if (event instanceof ExchangeStart<?> rawStart) {
            @SuppressWarnings("unchecked")
            ExchangeStart<T> start = (ExchangeStart<T>) rawStart;
            if (state != ClientRequestExchangeState.NEW) {
                start.result().completeExceptionally(invalidState("start"));
                return;
            }
            startAttempt(initialRequest, start.result());
        } else if (event instanceof ExchangeRetry<?> rawRetry) {
            @SuppressWarnings("unchecked")
            ExchangeRetry<T> retry = (ExchangeRetry<T>) rawRetry;
            if (state != ClientRequestExchangeState.ACTIVE) {
                retry.result().completeExceptionally(invalidState("retry"));
                return;
            }
            if (attemptCount >= retryPolicy.maxAttempts()) {
                RequestAttemptLimitException failure = new RequestAttemptLimitException(
                        retryPolicy.maxAttempts()
                );
                retry.result().completeExceptionally(failure);
                failExchange(failure, false);
                return;
            }
            startAttempt(retry.request(), retry.result());
        } else if (event instanceof ExchangeComplete<?> rawComplete) {
            @SuppressWarnings("unchecked")
            ExchangeComplete<T> complete = (ExchangeComplete<T>) rawComplete;
            if (state != ClientRequestExchangeState.ACTIVE) {
                complete.result().completeExceptionally(invalidState("complete"));
                return;
            }
            state = ClientRequestExchangeState.COMPLETED;
            completion.complete(complete.response());
            complete.result().complete(null);
            mailbox.close();
        } else if (event instanceof ExchangeFailed<?> rawFailed) {
            @SuppressWarnings("unchecked")
            ExchangeFailed<T> failed = (ExchangeFailed<T>) rawFailed;
            if (state != ClientRequestExchangeState.NEW
                    && state != ClientRequestExchangeState.ACTIVE) {
                failed.result().completeExceptionally(invalidState("fail"));
                return;
            }
            failExchange(failed.cause(), false);
            failed.result().complete(null);
        } else if (event instanceof ExchangeClose<?>) {
            if (state == ClientRequestExchangeState.NEW
                    || state == ClientRequestExchangeState.ACTIVE) {
                state = ClientRequestExchangeState.CLOSED;
                completion.completeExceptionally(new ClientRequestExchangeClosedException());
            }
            mailbox.close();
        }
    }

    private void startAttempt(
            SipRequest request,
            CompletableFuture<RequestAttempt<T>> result
    ) {
        int nextNumber = attemptCount + 1;
        SipRequest previous = currentAttempt == null ? null : currentAttempt.request();
        RequestAttemptContext context = new RequestAttemptContext(
                nextNumber,
                request,
                Optional.ofNullable(previous)
        );
        try {
            T handle = Objects.requireNonNull(attemptFactory.start(context), "attempt factory result");
            RequestAttempt<T> attempt = new RequestAttempt<>(nextNumber, request, handle);
            currentAttempt = attempt;
            attemptCount = nextNumber;
            state = ClientRequestExchangeState.ACTIVE;
            result.complete(attempt);
        } catch (Throwable cause) {
            result.completeExceptionally(cause);
            failExchange(cause, true);
        }
    }

    private void failExchange(Throwable cause, boolean reportFailure) {
        state = ClientRequestExchangeState.FAILED;
        completion.completeExceptionally(cause);
        if (reportFailure) {
            reportFailure(cause);
        }
        mailbox.close();
    }

    private IllegalStateException invalidState(String action) {
        return new IllegalStateException(
                "cannot " + action + " logical request exchange in state " + state
        );
    }

    private void submit(ClientRequestExchangeEvent<T> event, CompletableFuture<?> result) {
        try {
            mailbox.submit(event);
        } catch (Throwable cause) {
            result.completeExceptionally(cause);
        }
    }

    private void track(CompletableFuture<?> operation) {
        operations.add(operation);
        operation.whenComplete((ignored, failure) -> operations.remove(operation));
    }

    private void completePendingExceptionally(Throwable cause) {
        operations.forEach(operation -> operation.completeExceptionally(cause));
    }

    private void handleInfrastructureFailure(Throwable cause) {
        if (state == ClientRequestExchangeState.NEW
                || state == ClientRequestExchangeState.ACTIVE) {
            failExchange(cause, false);
        }
        reportFailure(cause);
    }

    private void reportFailure(Throwable cause) {
        try {
            failureListener.accept(cause);
        } catch (Throwable ignored) {
            System.getLogger(ClientRequestExchange.class.getName()).log(
                    System.Logger.Level.WARNING,
                    "client request exchange failure listener failed",
                    ignored
            );
        }
    }
}
