package org.loomsip.auth;

import org.junit.jupiter.api.Test;
import org.loomsip.exchange.ClientRequestExchange;
import org.loomsip.exchange.RequestAttempt;
import org.loomsip.exchange.RequestRetryPolicy;
import org.loomsip.message.SipHeaders;
import org.loomsip.message.SipMethod;
import org.loomsip.message.SipRequest;
import org.loomsip.message.SipResponse;
import org.loomsip.message.SipUri;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientAuthenticationCoordinatorTest {

    @Test
    void originChallengeStartsFreshAttemptAndLeavesOriginalRequestUntouched() {
        SipRequest initial = request(1, "first", false);
        List<SipRequest> started = new ArrayList<>();
        ClientRequestExchange<String> exchange = exchange(initial, started, 2);
        ClientAuthenticationCoordinator<String> coordinator = coordinator(
                exchange,
                lookup("alice", "secret"),
                retryFactory(2, "second"),
                ClientAuthenticationPolicy.DEFAULT
        );

        assertEquals(1, await(coordinator.start()).attemptNumber());
        ClientAuthenticationResult<String> result = await(coordinator.onResponse(challenge(
                401,
                "WWW-Authenticate",
                "Digest realm=\"origin\", nonce=\"nonce-1\", algorithm=MD5, qop=\"auth\""
        )));

        RequestAttempt<String> retry = result.retryAttempt().orElseThrow();
        assertEquals(ClientAuthenticationDisposition.RETRIED, result.disposition());
        assertEquals(2, retry.attemptNumber());
        assertEquals("2 OPTIONS", retry.request().headers().firstValue("CSeq").orElseThrow());
        String authorization = retry.request().headers().firstValue("Authorization").orElseThrow();
        assertTrue(authorization.startsWith("Digest username=\"alice\""));
        assertTrue(authorization.contains("algorithm=MD5"));
        assertEquals("00000001", authorizationParameter(authorization, "nc"));
        assertFalse(initial.headers().contains("Authorization"));
        assertEquals(2, started.size());
        assertEquals(ClientAuthenticationCoordinatorState.ACTIVE, coordinator.state());
    }

    @Test
    void proxyChallengeUsesProxyAuthorizationAndKeepsOriginAuthorization() {
        SipRequest initial = request(1, "first", true);
        List<SipRequest> started = new ArrayList<>();
        ClientRequestExchange<String> exchange = exchange(initial, started, 2);
        ClientAuthenticationCoordinator<String> coordinator = coordinator(
                exchange,
                lookup("proxy-user", "secret"),
                retryFactory(2, "second"),
                ClientAuthenticationPolicy.DEFAULT
        );
        await(coordinator.start());

        RequestAttempt<String> retry = await(coordinator.onResponse(challenge(
                407,
                "Proxy-Authenticate",
                "Digest realm=\"proxy\", nonce=\"proxy-nonce\", qop=auth"
        ))).retryAttempt().orElseThrow();

        assertEquals("Digest existing-origin", retry.request().headers().firstValue("Authorization").orElseThrow());
        assertTrue(retry.request().headers().firstValue("Proxy-Authorization").orElseThrow()
                .startsWith("Digest username=\"proxy-user\""));
    }

    @Test
    void selectsStrongestAllowedChallengeAndCompletesFinalResponses() {
        SipRequest initial = request(1, "first", false);
        List<SipRequest> started = new ArrayList<>();
        ClientRequestExchange<String> exchange = exchange(initial, started, 2);
        ClientAuthenticationCoordinator<String> coordinator = coordinator(
                exchange,
                lookup("alice", "secret"),
                retryFactory(2, "second"),
                ClientAuthenticationPolicy.DEFAULT
        );
        await(coordinator.start());

        SipResponse multiple = new SipResponse(401, "Unauthorized", SipHeaders.builder()
                .add("WWW-Authenticate", "Digest realm=\"origin\", nonce=\"md5\", algorithm=MD5, qop=auth")
                .add("WWW-Authenticate", "Digest realm=\"origin\", nonce=\"sha\", algorithm=SHA-256, qop=auth")
                .build());
        RequestAttempt<String> retry = await(coordinator.onResponse(multiple)).retryAttempt().orElseThrow();
        assertTrue(retry.request().headers().firstValue("Authorization").orElseThrow()
                .contains("algorithm=SHA-256"));

        SipResponse ok = new SipResponse(200, "OK", SipHeaders.empty());
        assertEquals(ClientAuthenticationDisposition.COMPLETED, await(coordinator.onResponse(ok)).disposition());
        assertEquals(ok, await(coordinator.completion()));
        assertEquals(ClientAuthenticationCoordinatorState.COMPLETED, coordinator.state());
    }

    @Test
    void provisionalResponseDoesNotCompleteAndRetryLimitFailsLogicalExchange() {
        SipRequest initial = request(1, "first", false);
        ClientRequestExchange<String> exchange = exchange(initial, new ArrayList<>(), 3);
        ClientAuthenticationCoordinator<String> coordinator = coordinator(
                exchange,
                lookup("alice", "secret"),
                retryFactory(2, "second"),
                new ClientAuthenticationPolicy(java.util.Set.of(DigestAlgorithm.MD5), 1)
        );
        await(coordinator.start());

        SipResponse ringing = new SipResponse(180, "Ringing", SipHeaders.empty());
        assertEquals(ClientAuthenticationDisposition.PROVISIONAL, await(coordinator.onResponse(ringing)).disposition());
        assertFalse(exchange.completion().toCompletableFuture().isDone());
        await(coordinator.onResponse(challenge(401, "WWW-Authenticate",
                "Digest realm=\"origin\", nonce=\"first\", qop=auth")));

        CompletionException failure = assertThrows(CompletionException.class, () -> await(coordinator.onResponse(challenge(
                401,
                "WWW-Authenticate",
                "Digest realm=\"origin\", nonce=\"second\", qop=auth"
        ))));
        assertInstanceOf(DigestAuthenticationException.class, failure.getCause());
        assertEquals(ClientAuthenticationCoordinatorState.FAILED, coordinator.state());
        assertThrows(CompletionException.class, () -> await(coordinator.completion()));
    }

    @Test
    void closeCompletesAnAcceptedStartThatHasNotReachedItsMailboxDrain() {
        SipRequest initial = request(1, "first", false);
        ClientRequestExchange<String> exchange = exchange(initial, new ArrayList<>(), 2);
        List<Runnable> queuedDrains = new ArrayList<>();
        ClientAuthenticationCoordinator<String> coordinator = new ClientAuthenticationCoordinator<>(
                exchange,
                lookup("alice", "secret"),
                retryFactory(2, "second"),
                queuedDrains::add
        );

        java.util.concurrent.CompletionStage<RequestAttempt<String>> start = coordinator.start();
        coordinator.close();

        CompletionException failure = assertThrows(CompletionException.class, () -> await(start));
        assertInstanceOf(ClientAuthenticationClosedException.class, failure.getCause());
        assertEquals(ClientAuthenticationCoordinatorState.CLOSED, coordinator.state());
        assertTrue(queuedDrains.size() == 1);
        queuedDrains.getFirst().run();
        await(coordinator.closed());
    }

    @Test
    void lateCredentialCompletionAfterCloseCannotStartRetry() {
        SipRequest initial = request(1, "first", false);
        List<SipRequest> started = new ArrayList<>();
        ClientRequestExchange<String> exchange = exchange(initial, started, 2);
        CompletableFuture<Optional<ClientDigestCredential>> credential = new CompletableFuture<>();
        ClientAuthenticationCoordinator<String> coordinator = coordinator(
                exchange,
                ignored -> credential,
                retryFactory(2, "second"),
                ClientAuthenticationPolicy.DEFAULT
        );
        await(coordinator.start());

        java.util.concurrent.CompletionStage<ClientAuthenticationResult<String>> response = coordinator.onResponse(challenge(
                401,
                "WWW-Authenticate",
                "Digest realm=\"origin\", nonce=\"nonce-1\", qop=auth"
        ));
        coordinator.close();
        credential.complete(Optional.of(new ClientDigestCredential("alice", "secret".toCharArray())));

        CompletionException failure = assertThrows(CompletionException.class, () -> await(response));
        assertInstanceOf(ClientAuthenticationClosedException.class, failure.getCause());
        assertEquals(ClientAuthenticationCoordinatorState.CLOSED, coordinator.state());
        assertEquals(1, exchange.attemptCount());
        assertEquals(List.of(initial), started);
        await(coordinator.closed());
    }

    private static ClientAuthenticationCoordinator<String> coordinator(
            ClientRequestExchange<String> exchange,
            ClientCredentialProvider provider,
            AuthenticatedRequestRetryFactory factory,
            ClientAuthenticationPolicy policy
    ) {
        return new ClientAuthenticationCoordinator<>(
                exchange,
                provider,
                factory,
                policy,
                new DigestChallengeParser(),
                new DigestCalculator(),
                () -> "fixed-cnonce",
                Runnable::run,
                failure -> {
                },
                16
        );
    }

    private static ClientRequestExchange<String> exchange(
            SipRequest initial,
            List<SipRequest> started,
            int maxAttempts
    ) {
        return new ClientRequestExchange<>(
                initial,
                context -> {
                    started.add(context.request());
                    return "transaction-" + context.attemptNumber();
                },
                new RequestRetryPolicy(maxAttempts),
                Runnable::run,
                failure -> {
                },
                16
        );
    }

    private static ClientCredentialProvider lookup(String username, String password) {
        return ignored -> CompletableFuture.completedFuture(Optional.of(
                new ClientDigestCredential(username, password.toCharArray())
        ));
    }

    private static AuthenticatedRequestRetryFactory retryFactory(long cseq, String branch) {
        return (previous, scope, challenge, authorization) -> CompletableFuture.completedFuture(
                previous.toBuilder()
                        .replaceHeader("Via", "SIP/2.0/UDP client.example.com;branch=z9hG4bK-" + branch)
                        .replaceHeader("CSeq", cseq + " OPTIONS")
                        .build()
        );
    }

    private static SipRequest request(long cseq, String branch, boolean originAuthorization) {
        SipHeaders.Builder headers = SipHeaders.builder()
                .add("Via", "SIP/2.0/UDP client.example.com;branch=z9hG4bK-" + branch)
                .add("From", "<sip:alice@example.com>;tag=client-tag")
                .add("To", "<sip:bob@example.com>")
                .add("Call-ID", "auth@example.com")
                .add("CSeq", cseq + " OPTIONS");
        if (originAuthorization) {
            headers.add("Authorization", "Digest existing-origin");
        }
        return new SipRequest(SipMethod.OPTIONS, SipUri.parse("sip:bob@example.com"), headers.build());
    }

    private static SipResponse challenge(int status, String header, String value) {
        return new SipResponse(status, "Challenge", SipHeaders.builder().add(header, value).build());
    }

    private static String authorizationParameter(String value, String name) {
        for (String parameter : value.substring("Digest ".length()).split(",")) {
            String[] parts = parameter.strip().split("=", 2);
            if (parts[0].equals(name)) {
                return parts[1].replace("\"", "");
            }
        }
        throw new AssertionError("missing authorization parameter: " + name);
    }

    private static <T> T await(java.util.concurrent.CompletionStage<T> stage) {
        return stage.toCompletableFuture().join();
    }
}
