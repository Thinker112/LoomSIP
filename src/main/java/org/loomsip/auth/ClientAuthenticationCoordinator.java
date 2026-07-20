package org.loomsip.auth;

import org.loomsip.concurrent.MailboxClosedException;
import org.loomsip.concurrent.SerialMailbox;
import org.loomsip.exchange.ClientRequestExchange;
import org.loomsip.exchange.RequestAttempt;
import org.loomsip.message.SipHeader;
import org.loomsip.message.SipRequest;
import org.loomsip.message.SipResponse;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Coordinates UAC Digest challenge retries above individual transactions.
 *
 * <pre>{@code
 * Transaction response callback
 *             |
 *             v
 * Authentication Coordinator Mailbox
 *       |              |             |
 *       v              v             v
 * Challenge parser  Credential     Retry factory
 *                   provider           |
 *       +--------------+---------------+
 *                      |
 *                      v
 *           Client Request Exchange -> new Transaction
 * }</pre>
 *
 * <p>Calls to {@link #start()} and {@link #onResponse(SipResponse)} may be
 * made concurrently. The coordinator serializes them on an on-demand mailbox;
 * its asynchronous credential and request-rebuild callbacks only enqueue the
 * next event. Therefore neither a credential provider nor a retry factory is
 * invoked on a Netty EventLoop by this class. The supplied executor should
 * normally create virtual threads.</p>、
 *
 * <p>Each 401 or 407 retry retains the logical request but creates a new
 * transaction attempt. The retry factory owns the new Via branch and CSeq.
 * For an in-dialog request it must obtain that CSeq through the Dialog
 * Mailbox. This coordinator calculates and applies exactly the Authorization
 * header for the challenged scope after that rebuild.</p>
 *
 * @param <T> concrete transaction handle type
 */
public final class ClientAuthenticationCoordinator<T> implements AutoCloseable {

    /** Default maximum number of queued coordinator events. */
    public static final int DEFAULT_MAILBOX_CAPACITY = 64;

    private final ClientRequestExchange<T> exchange;
    private final ClientCredentialProvider credentialProvider;
    private final AuthenticatedRequestRetryFactory retryFactory;
    private final ClientAuthenticationPolicy policy;
    private final DigestChallengeParser challengeParser;
    private final DigestCalculator calculator;
    private final CnonceGenerator cnonceGenerator;
    private final Consumer<? super Throwable> failureListener;
    private final SerialMailbox<Event<T>> mailbox;
    private final Map<NonceCountKey, Long> nonceCounts = new HashMap<>();
    private final Set<CompletableFuture<?>> operations = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean closeRequested = new AtomicBoolean();

    private volatile ClientAuthenticationCoordinatorState state = ClientAuthenticationCoordinatorState.NEW;

    /**
     * Creates a coordinator using the default Digest policy and a secure cnonce generator.
     *
     * @param exchange logical request exchange to drive
     * @param credentialProvider asynchronous one-use credential lookup
     * @param retryFactory request owner that creates fresh Via/CSeq values
     * @param executor executor used only while coordinator events are pending
     */
    public ClientAuthenticationCoordinator(
            ClientRequestExchange<T> exchange,
            ClientCredentialProvider credentialProvider,
            AuthenticatedRequestRetryFactory retryFactory,
            Executor executor
    ) {
        this(
                exchange,
                credentialProvider,
                retryFactory,
                ClientAuthenticationPolicy.DEFAULT,
                new DigestChallengeParser(),
                new DigestCalculator(),
                new SecureCnonceGenerator(),
                executor,
                failure -> {
                },
                DEFAULT_MAILBOX_CAPACITY
        );
    }

    /**
     * Creates a coordinator with explicit security and resource policies.
     *
     * @param exchange logical request exchange to drive
     * @param credentialProvider asynchronous one-use credential lookup
     * @param retryFactory request owner that creates fresh Via/CSeq values
     * @param policy allowed algorithms and challenge retry bound
     * @param challengeParser parser for individual Digest challenge fields
     * @param calculator stateless Digest response calculator
     * @param cnonceGenerator generator for each qop-auth retry
     * @param executor executor used only while coordinator events are pending
     * @param failureListener receives terminal non-close failures
     * @param mailboxCapacity maximum queued events excluding the currently handled event
     */
    public ClientAuthenticationCoordinator(
            ClientRequestExchange<T> exchange,
            ClientCredentialProvider credentialProvider,
            AuthenticatedRequestRetryFactory retryFactory,
            ClientAuthenticationPolicy policy,
            DigestChallengeParser challengeParser,
            DigestCalculator calculator,
            CnonceGenerator cnonceGenerator,
            Executor executor,
            Consumer<? super Throwable> failureListener,
            int mailboxCapacity
    ) {
        this.exchange = Objects.requireNonNull(exchange, "exchange");
        this.credentialProvider = Objects.requireNonNull(credentialProvider, "credentialProvider");
        this.retryFactory = Objects.requireNonNull(retryFactory, "retryFactory");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.challengeParser = Objects.requireNonNull(challengeParser, "challengeParser");
        this.calculator = Objects.requireNonNull(calculator, "calculator");
        this.cnonceGenerator = Objects.requireNonNull(cnonceGenerator, "cnonceGenerator");
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
     * @return stage yielding the initial attempt
     */
    public CompletionStage<RequestAttempt<T>> start() {
        CompletableFuture<RequestAttempt<T>> result = new CompletableFuture<>();
        submit(new StartRequested<>(result), result);
        return result.minimalCompletionStage();
    }

    /**
     * Processes one response from the current transaction attempt.
     *
     * <p>Provisional responses remain visible without completing the logical
     * exchange. A non-challenge final response completes it. For a 401 or 407,
     * this method completes only after credentials, a rebuilt request, and its
     * replacement transaction attempt are available.</p>
     *
     * @param response immutable response delivered by the transaction layer
     * @return classification and optional started retry attempt
     */
    public CompletionStage<ClientAuthenticationResult<T>> onResponse(SipResponse response) {
        CompletableFuture<ClientAuthenticationResult<T>> result = new CompletableFuture<>();
        submit(new ResponseReceived<>(Objects.requireNonNull(response, "response"), result), result);
        return result.minimalCompletionStage();
    }

    /**
     * Returns the logical request's terminal response stage.
     *
     * @return exchange completion stage
     */
    public CompletionStage<SipResponse> completion() {
        return exchange.completion();
    }

    /**
     * Returns the latest lifecycle state.
     *
     * @return coordinator state
     */
    public ClientAuthenticationCoordinatorState state() {
        return state;
    }

    /**
     * Returns completion of this coordinator's mailbox shutdown.
     *
     * @return close completion stage
     */
    public CompletionStage<Void> closed() {
        return mailbox.closed();
    }

    /**
     * Stops an unfinished coordinator and closes its logical request exchange.
     *
     * <p>An already returned start or response stage completes exceptionally.
     * Later credential or rebuild completions are ignored, and any credential
     * delivered after close is cleared before it is discarded.</p>
     */
    @Override
    public void close() {
        if (!closeRequested.compareAndSet(false, true)) {
            return;
        }
        state = ClientAuthenticationCoordinatorState.CLOSED;
        completePendingExceptionally(new ClientAuthenticationClosedException());
        exchange.close();
        mailbox.closeNow();
    }

    private void handleEvent(Event<T> event) {
        if (event instanceof StartRequested<T> start) {
            handleStart(start);
        } else if (event instanceof StartCompleted<T> startCompleted) {
            handleStartCompleted(startCompleted);
        } else if (event instanceof ResponseReceived<T> response) {
            handleResponse(response);
        } else if (event instanceof CredentialCompleted<T> credential) {
            handleCredentialCompleted(credential);
        } else if (event instanceof RetryRebuilt<T> rebuilt) {
            handleRetryRebuilt(rebuilt);
        } else if (event instanceof RetryStarted<T> retryStarted) {
            handleRetryStarted(retryStarted);
        } else if (event instanceof ExchangeCompleted<T> completed) {
            handleExchangeCompleted(completed);
        }
    }

    private void handleStart(StartRequested<T> start) {
        if (state != ClientAuthenticationCoordinatorState.NEW) {
            start.result().completeExceptionally(invalidState("start"));
            return;
        }
        state = ClientAuthenticationCoordinatorState.STARTING;
        try {
            exchange.start().whenComplete((attempt, failure) ->
                    submitStartCompleted(new StartCompleted<>(start.result(), attempt, unwrap(failure)))
            );
        } catch (Throwable cause) {
            fail(cause);
        }
    }

    private void handleStartCompleted(StartCompleted<T> completed) {
        if (isTerminal()) {
            completed.result().completeExceptionally(new ClientAuthenticationClosedException());
            return;
        }
        if (completed.failure() != null) {
            fail(completed.failure());
            return;
        }
        state = ClientAuthenticationCoordinatorState.ACTIVE;
        completed.result().complete(completed.attempt());
    }

    private void handleResponse(ResponseReceived<T> received) {
        if (state != ClientAuthenticationCoordinatorState.ACTIVE) {
            received.result().completeExceptionally(invalidState("process response"));
            return;
        }
        SipResponse response = received.response();
        if (response.statusCode() < 200) {
            received.result().complete(new ClientAuthenticationResult<>(
                    ClientAuthenticationDisposition.PROVISIONAL,
                    response,
                    Optional.empty()
            ));
            return;
        }
        DigestAuthenticationScope scope = DigestAuthenticationScope.fromStatusCode(response.statusCode());
        if (scope == null) {
            completeExchange(received);
            return;
        }
        beginChallenge(scope, received);
    }

    private void beginChallenge(
            DigestAuthenticationScope scope,
            ResponseReceived<T> received
    ) {
        DigestChallenge challenge;
        try {
            challenge = selectChallenge(scope, received.response());
            if (exchange.attemptCount() - 1 >= policy.maxChallengeRetries()) {
                throw new DigestAuthenticationException("Digest authentication retry limit reached");
            }
        } catch (Throwable cause) {
            fail(cause);
            return;
        }
        SipRequest previousRequest;
        try {
            previousRequest = exchange.currentAttempt()
                    .map(RequestAttempt::request)
                    .orElseThrow(() -> new IllegalStateException("active exchange has no transaction attempt"));
        } catch (Throwable cause) {
            fail(cause);
            return;
        }
        PendingChallenge<T> pending = new PendingChallenge<>(
                received.result(),
                received.response(),
                scope,
                challenge,
                previousRequest
        );
        state = ClientAuthenticationCoordinatorState.AWAITING_CREDENTIAL;
        try {
            CompletionStage<Optional<ClientDigestCredential>> lookup = credentialProvider.find(
                    new ClientCredentialRequest(scope, challenge, previousRequest)
            );
            if (lookup == null) {
                throw new DigestAuthenticationException("credential provider returned no completion stage");
            }
            lookup.whenComplete((credential, failure) -> submitCredentialCompleted(
                    new CredentialCompleted<>(pending, credential, unwrap(failure))
            ));
        } catch (Throwable cause) {
            fail(cause);
        }
    }

    private void handleCredentialCompleted(CredentialCompleted<T> completed) {
        if (isTerminal()) {
            closeCredential(completed.credential());
            return;
        }
        if (state != ClientAuthenticationCoordinatorState.AWAITING_CREDENTIAL) {
            closeCredential(completed.credential());
            fail(invalidState("receive credential"));
            return;
        }
        if (completed.failure() != null) {
            closeCredential(completed.credential());
            fail(new DigestAuthenticationException("Digest credential lookup failed"));
            return;
        }
        Optional<ClientDigestCredential> credential = completed.credential();
        if (credential == null || credential.isEmpty()) {
            fail(new DigestAuthenticationException("no matching Digest credential"));
            return;
        }

        PendingChallenge<T> pending = completed.pending();
        DigestAuthorization authorization;
        try (ClientDigestCredential value = credential.get()) {
            char[] password = value.copyPassword();
            try {
                long nonceCount = nextNonceCount(pending.scope(), pending.challenge(), value.username());
                String cnonce = requireCnonce(cnonceGenerator.nextCnonce());
                authorization = calculator.authorize(
                        pending.challenge(),
                        value.username(),
                        password,
                        pending.previousRequest(),
                        nonceCount,
                        cnonce
                );
            } finally {
                Arrays.fill(password, '\0');
            }
        } catch (Throwable cause) {
            fail(cause);
            return;
        }

        state = ClientAuthenticationCoordinatorState.BUILDING_RETRY;
        try {
            CompletionStage<SipRequest> rebuild = retryFactory.rebuild(
                    pending.previousRequest(),
                    pending.scope(),
                    pending.challenge(),
                    authorization
            );
            if (rebuild == null) {
                throw new DigestAuthenticationException("retry factory returned no completion stage");
            }
            rebuild.whenComplete((request, failure) -> submitRetryRebuilt(
                    new RetryRebuilt<>(pending, authorization, request, unwrap(failure))
            ));
        } catch (Throwable cause) {
            fail(cause);
        }
    }

    private void handleRetryRebuilt(RetryRebuilt<T> rebuilt) {
        if (isTerminal()) {
            return;
        }
        if (state != ClientAuthenticationCoordinatorState.BUILDING_RETRY) {
            fail(invalidState("receive rebuilt retry"));
            return;
        }
        if (rebuilt.failure() != null) {
            fail(new DigestAuthenticationException("authenticated request rebuild failed"));
            return;
        }
        if (rebuilt.request() == null) {
            fail(new DigestAuthenticationException("retry factory returned no request"));
            return;
        }
        SipRequest authenticated = DigestRequestAuthentication.apply(
                rebuilt.request(),
                rebuilt.pending().scope(),
                rebuilt.authorization()
        );
        state = ClientAuthenticationCoordinatorState.STARTING_RETRY;
        try {
            exchange.retry(authenticated).whenComplete((attempt, failure) -> submitRetryStarted(
                    new RetryStarted<>(rebuilt.pending(), attempt, unwrap(failure))
            ));
        } catch (Throwable cause) {
            fail(cause);
        }
    }

    private void handleRetryStarted(RetryStarted<T> started) {
        if (isTerminal()) {
            return;
        }
        if (started.failure() != null) {
            fail(started.failure());
            return;
        }
        state = ClientAuthenticationCoordinatorState.ACTIVE;
        started.pending().result().complete(new ClientAuthenticationResult<>(
                ClientAuthenticationDisposition.RETRIED,
                started.pending().response(),
                Optional.of(started.attempt())
        ));
    }

    private void completeExchange(ResponseReceived<T> received) {
        state = ClientAuthenticationCoordinatorState.COMPLETING;
        try {
            exchange.complete(received.response()).whenComplete((ignored, failure) -> submitExchangeCompleted(
                    new ExchangeCompleted<>(received.result(), received.response(), unwrap(failure))
            ));
        } catch (Throwable cause) {
            fail(cause);
        }
    }

    private void handleExchangeCompleted(ExchangeCompleted<T> completed) {
        if (isTerminal()) {
            return;
        }
        if (completed.failure() != null) {
            fail(completed.failure());
            return;
        }
        state = ClientAuthenticationCoordinatorState.COMPLETED;
        completed.result().complete(new ClientAuthenticationResult<>(
                ClientAuthenticationDisposition.COMPLETED,
                completed.response(),
                Optional.empty()
        ));
        mailbox.close();
    }

    private DigestChallenge selectChallenge(DigestAuthenticationScope scope, SipResponse response) {
        List<DigestChallenge> parsed = new ArrayList<>();
        boolean digestSeen = false;
        for (SipHeader header : response.headers().all(scope.challengeHeaderName())) {
            if (!challengeParser.isDigestChallenge(header.value())) {
                continue;
            }
            digestSeen = true;
            parsed.add(challengeParser.parse(header.value()));
        }
        if (!digestSeen) {
            throw new DigestAuthenticationException("authentication challenge has no Digest scheme");
        }
        return parsed.stream()
                .filter(policy::supports)
                .max(Comparator.comparingInt(value -> value.algorithm().strength()))
                .orElseThrow(() -> new DigestUnsupportedChallengeException(
                        "authentication challenge has no supported algorithm with qop=auth"
                ));
    }

    private long nextNonceCount(
            DigestAuthenticationScope scope,
            DigestChallenge challenge,
            String username
    ) {
        NonceCountKey key = new NonceCountKey(scope, challenge.realm(), challenge.nonce(), username, challenge.algorithm());
        long next = nonceCounts.getOrDefault(key, 0L) + 1;
        if (next > 0xffff_ffffL) {
            throw new DigestAuthenticationException("Digest nonce count is exhausted");
        }
        nonceCounts.put(key, next);
        return next;
    }

    private void fail(Throwable cause) {
        if (isTerminal()) {
            return;
        }
        state = ClientAuthenticationCoordinatorState.FAILED;
        completePendingExceptionally(cause);
        try {
            exchange.fail(cause);
        } catch (Throwable ignored) {
            // The exchange can already be terminal after an attempt factory failure.
        }
        reportFailure(cause);
        mailbox.close();
    }

    private void handleInfrastructureFailure(Throwable cause) {
        fail(cause);
    }

    private void completePendingExceptionally(Throwable cause) {
        operations.forEach(operation -> operation.completeExceptionally(cause));
    }

    private void submitStartCompleted(StartCompleted<T> event) {
        submitFromCallback(event, null);
    }

    private void submitCredentialCompleted(CredentialCompleted<T> event) {
        submitFromCallback(event, () -> closeCredential(event.credential()));
    }

    private void submitRetryRebuilt(RetryRebuilt<T> event) {
        submitFromCallback(event, null);
    }

    private void submitRetryStarted(RetryStarted<T> event) {
        submitFromCallback(event, null);
    }

    private void submitExchangeCompleted(ExchangeCompleted<T> event) {
        submitFromCallback(event, null);
    }

    private void submitFromCallback(Event<T> event, Runnable rejectedAction) {
        if (isTerminal()) {
            if (rejectedAction != null) {
                rejectedAction.run();
            }
            return;
        }
        try {
            mailbox.submit(event);
        } catch (Throwable ignored) {
            if (rejectedAction != null) {
                rejectedAction.run();
            }
        }
    }

    private void submit(Event<T> event, CompletableFuture<?> result) {
        track(result);
        if (closeRequested.get()) {
            result.completeExceptionally(new ClientAuthenticationClosedException());
            return;
        }
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

    private boolean isTerminal() {
        return state == ClientAuthenticationCoordinatorState.COMPLETED
                || state == ClientAuthenticationCoordinatorState.FAILED
                || state == ClientAuthenticationCoordinatorState.CLOSED;
    }

    private IllegalStateException invalidState(String action) {
        return new IllegalStateException(
                "cannot " + action + " client authentication coordinator in state " + state
        );
    }

    private void reportFailure(Throwable cause) {
        try {
            failureListener.accept(cause);
        } catch (Throwable ignored) {
            System.getLogger(ClientAuthenticationCoordinator.class.getName()).log(
                    System.Logger.Level.WARNING,
                    "client authentication failure listener failed",
                    ignored
            );
        }
    }

    private static Throwable unwrap(Throwable failure) {
        if (failure instanceof java.util.concurrent.CompletionException completion && completion.getCause() != null) {
            return completion.getCause();
        }
        return failure;
    }

    private static String requireCnonce(String cnonce) {
        if (cnonce == null || cnonce.isBlank()) {
            throw new DigestAuthenticationException("cnonce generator returned an empty value");
        }
        return cnonce;
    }

    private static void closeCredential(Optional<ClientDigestCredential> credential) {
        if (credential != null) {
            credential.ifPresent(ClientDigestCredential::close);
        }
    }

    private record NonceCountKey(
            DigestAuthenticationScope scope,
            String realm,
            String nonce,
            String username,
            DigestAlgorithm algorithm
    ) {
    }

    private sealed interface Event<T> permits StartRequested, StartCompleted, ResponseReceived,
            CredentialCompleted, RetryRebuilt, RetryStarted, ExchangeCompleted {
    }

    private record StartRequested<T>(CompletableFuture<RequestAttempt<T>> result) implements Event<T> {
    }

    private record StartCompleted<T>(
            CompletableFuture<RequestAttempt<T>> result,
            RequestAttempt<T> attempt,
            Throwable failure
    ) implements Event<T> {
    }

    private record ResponseReceived<T>(
            SipResponse response,
            CompletableFuture<ClientAuthenticationResult<T>> result
    ) implements Event<T> {
    }

    private record PendingChallenge<T>(
            CompletableFuture<ClientAuthenticationResult<T>> result,
            SipResponse response,
            DigestAuthenticationScope scope,
            DigestChallenge challenge,
            SipRequest previousRequest
    ) {
    }

    private record CredentialCompleted<T>(
            PendingChallenge<T> pending,
            Optional<ClientDigestCredential> credential,
            Throwable failure
    ) implements Event<T> {
    }

    private record RetryRebuilt<T>(
            PendingChallenge<T> pending,
            DigestAuthorization authorization,
            SipRequest request,
            Throwable failure
    ) implements Event<T> {
    }

    private record RetryStarted<T>(
            PendingChallenge<T> pending,
            RequestAttempt<T> attempt,
            Throwable failure
    ) implements Event<T> {
    }

    private record ExchangeCompleted<T>(
            CompletableFuture<ClientAuthenticationResult<T>> result,
            SipResponse response,
            Throwable failure
    ) implements Event<T> {
    }

}
