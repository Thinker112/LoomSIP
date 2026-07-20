package org.loomsip.auth;

import org.loomsip.message.SipHeaders;
import org.loomsip.message.SipRequest;
import org.loomsip.message.SipResponse;
import org.loomsip.message.SipResponses;
import org.loomsip.transaction.invite.InviteServerHandle;
import org.loomsip.transaction.invite.InviteServerListener;
import org.loomsip.transaction.noninvite.NonInviteServerListener;
import org.loomsip.transaction.noninvite.ServerTransactionHandle;
import org.loomsip.transaction.timer.SipTimer;
import org.loomsip.transport.TransportContext;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;

/**
 * UAS Digest gate placed before Dialog routing and application callbacks.
 *
 * <pre>{@code
 * IST / NIST callback
 *          |
 *          v
 * Server Authentication Gate
 *    |                  |
 *    v                  v
 *  401 response   Dialog Bridge -> TU
 * }</pre>
 *
 * <p>The gate has no Netty dependency. It parses and validates only the
 * initial request notification from a server transaction. A successful request
 * is passed to the supplied downstream listener; a failed request is answered
 * on the existing transaction and never reaches a Dialog Mailbox or TU.</p>
 */
public final class ServerAuthenticationGate {

    private final ServerAuthenticationPolicy policy;
    private final DigestCredentialRepository credentials;
    private final DigestNonceManager nonces;
    private final DigestAuthorizationParser authorizationParser;
    private final DigestVerifier verifier;
    private final ServerTagGenerator tagGenerator;

    /**
     * Creates a gate using default parser and verifier implementations.
     *
     * @param policy UAS authentication and resource policy
     * @param credentials asynchronous HA1 credential repository
     * @param nonces nonce lifecycle and replay-protection owner
     */
    public ServerAuthenticationGate(
            ServerAuthenticationPolicy policy,
            DigestCredentialRepository credentials,
            DigestNonceManager nonces
    ) {
        this(
                policy,
                credentials,
                nonces,
                new DigestAuthorizationParser(),
                new DigestVerifier(),
                new SecureServerTagGenerator()
        );
    }

    /**
     * Creates a gate with explicit parser, verifier, and To-tag collaborators.
     *
     * @param policy UAS authentication and resource policy
     * @param credentials asynchronous HA1 credential repository
     * @param nonces nonce lifecycle and replay-protection owner
     * @param authorizationParser parser for Authorization headers
     * @param verifier stateless Digest verifier
     * @param tagGenerator generator for 401 To-tags
     */
    public ServerAuthenticationGate(
            ServerAuthenticationPolicy policy,
            DigestCredentialRepository credentials,
            DigestNonceManager nonces,
            DigestAuthorizationParser authorizationParser,
            DigestVerifier verifier,
            ServerTagGenerator tagGenerator
    ) {
        this.policy = Objects.requireNonNull(policy, "policy");
        this.credentials = Objects.requireNonNull(credentials, "credentials");
        this.nonces = Objects.requireNonNull(nonces, "nonces");
        this.authorizationParser = Objects.requireNonNull(authorizationParser, "authorizationParser");
        this.verifier = Objects.requireNonNull(verifier, "verifier");
        this.tagGenerator = Objects.requireNonNull(tagGenerator, "tagGenerator");
    }

    /**
     * Authenticates one request or returns a generic 401 challenge response.
     *
     * <p>Credential lookup is asynchronous. Completion runs on the repository's
     * executor; listener wrappers returned by this class explicitly dispatch
     * the final result to their supplied protocol callback executor.</p>
     *
     * @param request immutable initial server request
     * @return authenticated result, or a challenge result
     */
    public CompletionStage<ServerAuthenticationResult> authenticate(SipRequest request) {
        Objects.requireNonNull(request, "request");
        List<org.loomsip.message.SipHeader> fields = request.headers().all("Authorization");
        if (fields.size() != 1 || !authorizationParser.isDigestAuthorization(fields.getFirst().value())) {
            return CompletableFuture.completedFuture(challenge(request, false));
        }

        DigestAuthorizationRequest authorization;
        try {
            authorization = authorizationParser.parse(fields.getFirst().value());
            if (!policy.realm().equals(authorization.realm())
                    || !policy.allowedAlgorithms().contains(authorization.algorithm())
                    || policy.opaque().filter(value -> !authorization.opaque().filter(value::equals).isPresent())
                    .isPresent()) {
                return CompletableFuture.completedFuture(challenge(request, false));
            }
        } catch (DigestAuthenticationException | IllegalArgumentException exception) {
            return CompletableFuture.completedFuture(challenge(request, false));
        }

        DigestNonceValidation nonceValidation = nonces.validate(
                authorization.nonce(),
                authorization.realm(),
                authorization.algorithm()
        );
        if (nonceValidation.status() != DigestNonceStatus.VALID) {
            return CompletableFuture.completedFuture(
                    challenge(request, nonceValidation.status() == DigestNonceStatus.STALE)
            );
        }

        CompletionStage<Optional<DigestCredentialRecord>> lookup;
        try {
            lookup = credentials.find(authorization.realm(), authorization.username(), authorization.algorithm());
            if (lookup == null) {
                return CompletableFuture.failedFuture(
                        new ServerAuthenticationException("Digest credential repository returned no completion stage")
                );
            }
        } catch (Throwable cause) {
            return CompletableFuture.failedFuture(new ServerAuthenticationException("Digest credential lookup failed"));
        }

        DigestNonce nonce = nonceValidation.nonce().orElseThrow();
        return lookup.handle((credential, failure) -> {
            if (failure != null) {
                throw new ServerAuthenticationException("Digest credential lookup failed");
            }
            if (credential == null || credential.isEmpty()) {
                return challenge(request, false);
            }
            DigestCredentialRecord record = credential.get();
            if (verifier.verify(request, authorization, record, nonce) != DigestVerificationResult.VALID) {
                return challenge(request, false);
            }
            long nonceCount = Long.parseLong(authorization.nonceCount(), 16);
            DigestNonceStatus consumeStatus = nonces.consumeNonceCount(
                    authorization.nonce(),
                    authorization.realm(),
                    authorization.algorithm(),
                    authorization.username(),
                    authorization.cnonce(),
                    nonceCount
            );
            if (consumeStatus == DigestNonceStatus.VALID) {
                return ServerAuthenticationResult.authenticated();
            }
            return challenge(request, consumeStatus == DigestNonceStatus.STALE);
        });
    }

    /**
     * Wraps an INVITE server listener so only authenticated INVITEs reach it.
     *
     * @param downstream listener, normally {@code DialogTransactionBridge.serverListener()}
     * @param callbackExecutor virtual-thread protocol callback executor
     * @return authentication-gated listener
     */
    public InviteServerListener inviteListener(InviteServerListener downstream, Executor callbackExecutor) {
        Objects.requireNonNull(downstream, "downstream");
        Objects.requireNonNull(callbackExecutor, "callbackExecutor");
        return new InviteServerListener() {
            @Override
            public void onInvite(InviteServerHandle transaction, SipRequest request, TransportContext context) {
                authenticate(request).whenCompleteAsync((result, failure) -> {
                    if (failure != null) {
                        sendInternalError(transaction, request, downstream, failure);
                    } else if (result.disposition() == ServerAuthenticationDisposition.AUTHENTICATED) {
                        downstream.onInvite(transaction, request, context);
                    } else {
                        sendChallenge(transaction, result.challenge().orElseThrow(), downstream);
                    }
                }, callbackExecutor);
            }

            @Override
            public void onCancel(InviteServerHandle transaction, SipRequest cancel, TransportContext context) {
                downstream.onCancel(transaction, cancel, context);
            }

            @Override
            public void onAck(InviteServerHandle transaction, SipRequest ack, TransportContext context) {
                downstream.onAck(transaction, ack, context);
            }

            @Override
            public void onUnmatchedAck(SipRequest ack, TransportContext context) {
                downstream.onUnmatchedAck(ack, context);
            }

            @Override
            public void onTimeout(InviteServerHandle transaction, SipTimer timer) {
                downstream.onTimeout(transaction, timer);
            }

            @Override
            public void onTransportFailure(InviteServerHandle transaction, Throwable cause) {
                downstream.onTransportFailure(transaction, cause);
            }

            @Override
            public void onTerminated(InviteServerHandle transaction) {
                downstream.onTerminated(transaction);
            }

            @Override
            public void onLayerError(Throwable cause) {
                downstream.onLayerError(cause);
            }
        };
    }

    /**
     * Wraps a Non-INVITE server listener so only authenticated requests reach it.
     *
     * @param downstream listener, normally {@code DialogTransactionBridge.nonInviteServerListener()}
     * @param callbackExecutor virtual-thread protocol callback executor
     * @return authentication-gated listener
     */
    public NonInviteServerListener nonInviteListener(
            NonInviteServerListener downstream,
            Executor callbackExecutor
    ) {
        Objects.requireNonNull(downstream, "downstream");
        Objects.requireNonNull(callbackExecutor, "callbackExecutor");
        return new NonInviteServerListener() {
            @Override
            public void onRequest(ServerTransactionHandle transaction, SipRequest request, TransportContext context) {
                authenticate(request).whenCompleteAsync((result, failure) -> {
                    if (failure != null) {
                        sendInternalError(transaction, request, downstream, failure);
                    } else if (result.disposition() == ServerAuthenticationDisposition.AUTHENTICATED) {
                        downstream.onRequest(transaction, request, context);
                    } else {
                        sendChallenge(transaction, result.challenge().orElseThrow(), downstream);
                    }
                }, callbackExecutor);
            }

            @Override
            public void onTransportFailure(ServerTransactionHandle transaction, Throwable cause) {
                downstream.onTransportFailure(transaction, cause);
            }

            @Override
            public void onTerminated(ServerTransactionHandle transaction) {
                downstream.onTerminated(transaction);
            }

            @Override
            public void onLayerError(Throwable cause) {
                downstream.onLayerError(cause);
            }
        };
    }

    private ServerAuthenticationResult challenge(SipRequest request, boolean stale) {
        DigestNonce nonce = nonces.issue();
        String header = challengeHeader(nonce, stale);
        SipResponse base = SipResponses.createResponse(request, 401, "Unauthorized", tagGenerator.nextTag());
        SipResponse response = new SipResponse(
                base.version(),
                base.statusCode(),
                base.reasonPhrase(),
                base.headers().toBuilder().add("WWW-Authenticate", header).build(),
                base.body()
        );
        return ServerAuthenticationResult.challenged(response);
    }

    private String challengeHeader(DigestNonce nonce, boolean stale) {
        StringBuilder value = new StringBuilder("Digest realm=")
                .append(quote(policy.realm()))
                .append(", nonce=").append(quote(nonce.value()))
                .append(", algorithm=").append(nonce.algorithm().wireName())
                .append(", qop=").append(quote(DigestQop.AUTH.wireName()))
                .append(", stale=").append(stale);
        policy.opaque().ifPresent(opaque -> value.append(", opaque=").append(quote(opaque)));
        if (nonce.charset() == DigestCharset.UTF_8) {
            value.append(", charset=UTF-8");
        }
        return value.toString();
    }

    private void sendChallenge(
            InviteServerHandle transaction,
            SipResponse response,
            InviteServerListener downstream
    ) {
        try {
            transaction.sendResponse(response);
        } catch (Throwable cause) {
            downstream.onLayerError(new ServerAuthenticationException("failed to send Digest challenge"));
        }
    }

    private void sendChallenge(
            ServerTransactionHandle transaction,
            SipResponse response,
            NonInviteServerListener downstream
    ) {
        try {
            transaction.sendResponse(response);
        } catch (Throwable cause) {
            downstream.onLayerError(new ServerAuthenticationException("failed to send Digest challenge"));
        }
    }

    private void sendInternalError(
            InviteServerHandle transaction,
            SipRequest request,
            InviteServerListener downstream,
            Throwable failure
    ) {
        try {
            transaction.sendResponse(SipResponses.createResponse(request, 500, "Server Internal Error", tagGenerator.nextTag()));
        } catch (Throwable ignored) {
            // The downstream error callback remains the only remaining reporting path.
        }
        downstream.onLayerError(new ServerAuthenticationException("Digest authentication infrastructure failed"));
    }

    private void sendInternalError(
            ServerTransactionHandle transaction,
            SipRequest request,
            NonInviteServerListener downstream,
            Throwable failure
    ) {
        try {
            transaction.sendResponse(SipResponses.createResponse(request, 500, "Server Internal Error", tagGenerator.nextTag()));
        } catch (Throwable ignored) {
            // The downstream error callback remains the only remaining reporting path.
        }
        downstream.onLayerError(new ServerAuthenticationException("Digest authentication infrastructure failed"));
    }

    private static String quote(String value) {
        return '"' + value.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
    }
}
